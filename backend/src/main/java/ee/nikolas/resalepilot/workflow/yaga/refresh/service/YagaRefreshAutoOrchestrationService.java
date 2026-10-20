package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import com.google.api.client.auth.oauth2.TokenResponseException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDriveDownloadException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingPreconditionException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshSchedulerProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.hide-execution-enabled:false}' == 'true'"
)
public class YagaRefreshAutoOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(
            YagaRefreshAutoOrchestrationService.class
    );

    private final YagaRefreshSchedulerProperties properties;
    private final YagaRefreshRunService runService;
    private final YagaAccountService accountService;
    private final YagaRefreshExecutionService publicationService;
    private final ObjectProvider<YagaRefreshHidingExecutionService>
            hidingServiceProvider;
    private final YagaRefreshRunRepository runRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final YagaRefreshSourceValidationService sourceValidationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public YagaRefreshAutoOrchestrationService(
            YagaRefreshSchedulerProperties properties,
            YagaRefreshRunService runService,
            YagaAccountService accountService,
            YagaRefreshExecutionService publicationService,
            ObjectProvider<YagaRefreshHidingExecutionService>
                    hidingServiceProvider,
            YagaRefreshRunRepository runRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            YagaRefreshSourceValidationService sourceValidationService
    ) {
        this.properties = properties;
        this.runService = runService;
        this.accountService = accountService;
        this.publicationService = publicationService;
        this.hidingServiceProvider = hidingServiceProvider;
        this.runRepository = runRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.sourceValidationService = sourceValidationService;
    }

    public YagaRefreshAutoOrchestrationService(
            YagaRefreshSchedulerProperties properties,
            YagaRefreshRunService runService,
            YagaRefreshExecutionService publicationService,
            ObjectProvider<YagaRefreshHidingExecutionService>
                    hidingServiceProvider,
            YagaRefreshRunRepository runRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            YagaRefreshSourceValidationService sourceValidationService
    ) {
        this(
                properties,
                runService,
                null,
                publicationService,
                hidingServiceProvider,
                runRepository,
                transactionManager,
                clock,
                sourceValidationService
        );
    }

    public YagaRefreshAutoRunResult runScheduled(String idempotencyKey) {
        if (!running.compareAndSet(false, true)) {
            log.info(
                    "Yaga AUTO refresh scheduler skipped: reason={}",
                    "active-run-exists"
            );
            return YagaRefreshAutoRunResult.skipped(
                    "An active Yaga refresh run is already processing"
            );
        }

        try {
            YagaRefreshAutoRunResult lastResult =
                    YagaRefreshAutoRunResult.skipped(
                            "No enabled Yaga accounts are configured for AUTO refresh"
                    );
            for (YagaAccount account : autoRefreshAccounts()) {
                lastResult = runScheduledAccount(account, idempotencyKey);
            }
            return lastResult;
        } finally {
            running.set(false);
        }
    }

    public YagaRefreshAutoRunResult runOnDemand(
            Long accountId,
            int batchSize,
            String idempotencyKey
    ) {
        if (!running.compareAndSet(false, true)) {
            log.info(
                    "Yaga ON_DEMAND AUTO refresh skipped: reason={}",
                    "active-run-exists"
            );
            return YagaRefreshAutoRunResult.skipped(
                    "An active Yaga refresh run is already processing"
            );
        }

        try {
            Optional<YagaRefreshRunResponse> created =
                    runService.startOnDemandAutoRun(
                            accountId,
                            idempotencyKey,
                            batchSize
                    );
            if (created.isEmpty()) {
                return YagaRefreshAutoRunResult.skipped(
                        "An active Yaga refresh run is already processing"
                );
            }

            YagaRefreshRunResponse run = created.get();
            log.info(
                    "Yaga ON_DEMAND AUTO refresh run created: accountId={} shopSlug={} runId={} selectedJobCount={}",
                    run.yagaAccountId(),
                    run.shopSlug(),
                    run.runId(),
                    run.selectedJobCount()
            );
            return processRun(run.runId(), true);
        } finally {
            running.set(false);
        }
    }

    public YagaRefreshAutoRunResult runOnDemandListing(
            Long accountId, Long listingId, String idempotencyKey
    ) {
        if (!running.compareAndSet(false, true)) {
            return YagaRefreshAutoRunResult.skipped(
                    "An active Yaga refresh run is already processing"
            );
        }
        try {
            YagaRefreshRunResponse run = runService.startOnDemandListingRun(
                    accountId, listingId, idempotencyKey
            );
            return processRun(run.runId());
        } finally {
            running.set(false);
        }
    }

    private java.util.List<YagaAccount> autoRefreshAccounts() {
        if (accountService != null) {
            return accountService.autoRefreshAccounts();
        }
        YagaAccount account = new YagaAccount(
                "Default Yaga account",
                "nik-ar",
                "../playwright/.auth/yaga-state.json",
                properties.batchSize()
        );
        account.setId(1L);
        return java.util.List.of(account);
    }

    private YagaRefreshAutoRunResult runScheduledAccount(
            YagaAccount account,
            String idempotencyKey
    ) {
        String accountKey = idempotencyKey + "-account-" + account.getId();
        Optional<YagaRefreshRunResponse> created = accountService == null
                ? runService.startScheduledAutoRun(
                        idempotencyKey,
                        account.getBatchSize()
                )
                : runService.startScheduledAutoRun(
                        account,
                        accountKey,
                        account.getBatchSize()
                );
        if (created.isEmpty()) {
            log.info(
                    "Yaga AUTO refresh scheduler skipped account: accountId={} shopSlug={} reason={}",
                    account.getId(),
                    account.getShopSlug(),
                    "active-run-exists"
            );
            return YagaRefreshAutoRunResult.skipped(
                    "An active Yaga refresh run is already processing"
            );
        }

        YagaRefreshRunResponse run = created.get();
        log.info(
                "Yaga AUTO refresh run created: accountId={} shopSlug={} runId={} selectedJobCount={}",
                account.getId(),
                account.getShopSlug(),
                run.runId(),
                run.selectedJobCount()
        );
        return processRun(run.runId());
    }

    YagaRefreshAutoRunResult processRun(java.util.UUID runId) {
        return processRun(runId, false);
    }

    private YagaRefreshAutoRunResult processRun(java.util.UUID runId, boolean batchOnDemand) {
        while (true) {
            YagaRefreshJob job = currentJob(runId).orElse(null);
            if (job == null) {
                YagaRefreshRun run = runRepository.findWithJobsById(runId)
                        .orElseThrow();
                if (run.getStatus() == YagaRefreshRunStatus.COMPLETED) {
                    log.info(
                            "Yaga AUTO refresh run completed: accountId={} shopSlug={} runId={} selectedJobCount={}",
                            run.getYagaAccount().getId(),
                            run.getYagaAccount().getShopSlug(),
                            run.getId(),
                            run.getJobs().size()
                    );
                }
                return new YagaRefreshAutoRunResult(
                        run.getId(),
                        true,
                        isTerminal(run.getStatus()),
                        run.getStatus(),
                        null
                );
            }

            try {
                if (job.getStatus() == YagaRefreshJobStatus.SELECTED) {
                    if (batchOnDemand && skipInactiveSource(runId, job.getId())) {
                        continue;
                    }
                    if (!autoPublish(job)) {
                        return stopped(runId);
                    }
                    continue;
                }

                if (job.getStatus() ==
                        YagaRefreshJobStatus.NEW_LISTING_CONFIRMED) {
                    if (!autoHide(job)) {
                        return stopped(runId);
                    }
                    continue;
                }

                markUnsafe(
                        runId,
                        job.getId(),
                        "AUTO_REFRESH_UNSAFE_STATE",
                        "Automatic Yaga refresh stopped because the current job requires operator review"
                );
                return stopped(runId);

            } catch (RuntimeException exception) {
                AutoStepFailure failure = autoStepFailure(exception);
                YagaPublishingFormDiagnostics formDiagnostics =
                        publishingDiagnostics(exception);
                String openFormDiagnostic = formDiagnostics == null ||
                        formDiagnostics.openFormDiagnostic() == null
                        ? null
                        : formDiagnostics.openFormDiagnostic().safeSummary();
                log.warn(
                        "Yaga AUTO refresh step failed: runId={} jobId={} accountId={} shopSlug={} operationStage={} exceptionClass={} rootCauseClass={} safeErrorCode={}",
                        runId,
                        job.getId(),
                        job.getRun().getYagaAccount() == null
                                ? null
                                : job.getRun().getYagaAccount().getId(),
                        job.getRun().getYagaAccount() == null
                                ? null
                                : job.getRun().getYagaAccount().getShopSlug(),
                        failure.operationStage(),
                        failure.exceptionClass(),
                        failure.rootCauseClass(),
                        failure.safeErrorCode()
                );
                markUnsafe(
                        runId,
                        job.getId(),
                        failure.safeErrorCode(),
                        failure.safeMessage(),
                        openFormDiagnostic
                );
                return stopped(runId);
            }
        }
    }

    private boolean skipInactiveSource(java.util.UUID runId, java.util.UUID jobId) {
        SourcePreflight snapshot = transactionTemplate.execute(status -> {
            YagaRefreshRun run = runRepository.findForUpdateWithJobsById(runId).orElseThrow();
            YagaRefreshJob job = requirePreflightJob(run, jobId);
            return new SourcePreflight(run.getYagaAccount().getId(), job.getProduct().getId(),
                    job.getOldListing().getId(), job.getVersion(), source(job));
        });

        // Only this read-only preflight may turn confirmed inactivity into a skip.
        // The publication pipeline retains its independent checks and recovery behavior.
        try {
            sourceValidationService.requireActive(snapshot.source());
            return false;
        } catch (YagaRefreshSourceInactiveException inactive) {
            transactionTemplate.executeWithoutResult(status -> {
                YagaRefreshRun run = runRepository.findForUpdateWithJobsById(runId).orElseThrow();
                YagaRefreshJob job = requirePreflightJob(run, jobId);
                if (!Objects.equals(snapshot.accountId(), run.getYagaAccount().getId()) ||
                        !Objects.equals(snapshot.productId(), job.getProduct().getId()) ||
                        !Objects.equals(snapshot.listingId(), job.getOldListing().getId()) ||
                        !Objects.equals(snapshot.jobVersion(), job.getVersion()) ||
                        !snapshot.source().equals(source(job))) {
                    throw new YagaRefreshInvalidStateException("Source changed during refresh preflight");
                }
                Instant now = clock.instant();
                if (!YagaSelectedJobLifecycle.finish(job, YagaRefreshJobStatus.SKIPPED, now)) {
                    throw new YagaRefreshInvalidStateException("Refresh job is no longer unexecuted");
                }
                job.setLastErrorCode("SOURCE_NOT_ACTIVE");
                job.setLastSafeErrorMessage("Source verified inactive before publication preparation; job skipped");
                if (YagaRefreshSequencingGuard.currentJob(run).isEmpty()) {
                    // Match existing finalization: a run containing SKIPPED jobs has errors.
                    run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
                    run.setCompletedAt(now);
                }
            });
            log.info("Yaga ON_DEMAND refresh source skipped: runId={} jobId={} safeErrorCode=SOURCE_NOT_ACTIVE",
                    runId, jobId);
            return true;
        }
    }

    private YagaRefreshJob requirePreflightJob(YagaRefreshRun run, java.util.UUID jobId) {
        if (run.getStatus() != YagaRefreshRunStatus.PROCESSING ||
                run.getMode() != YagaRefreshRunMode.AUTO ||
                run.getTriggerType() != YagaRefreshTriggerType.ON_DEMAND ||
                !run.getYagaAccount().isEnabled()) {
            throw new YagaRefreshInvalidStateException("Run is not eligible for source preflight");
        }
        YagaRefreshJob job = run.getJobs().stream()
                .filter(candidate -> candidate.getId().equals(jobId)).findFirst().orElseThrow();
        YagaRefreshSequencingGuard.requireCurrentJob(run, job);
        if (!YagaSelectedJobLifecycle.unexecuted(job)) {
            throw new YagaRefreshInvalidStateException("Source preflight requires an unexecuted selected job");
        }
        var listing = job.getOldListing();
        if (listing.getMarketplace() != ee.nikolas.resalepilot.marketplace.entity.Marketplace.YAGA ||
                listing.getYagaAccount() == null ||
                !Objects.equals(run.getYagaAccount().getId(), listing.getYagaAccount().getId()) ||
                !Objects.equals(run.getYagaAccount().getShopSlug(), job.getOldShopSlug()) ||
                !Objects.equals(job.getProduct().getId(), listing.getProduct().getId()) ||
                !Objects.equals(job.getOldExternalListingId(), listing.getExternalListingId()) ||
                !Objects.equals(job.getOldShopSlug(), listing.getShopSlug()) ||
                !Objects.equals(job.getOldProductSlug(), listing.getProductSlug()) ||
                !Objects.equals(job.getOldPublicUrl(), listing.getExternalUrl())) {
            throw new YagaRefreshInvalidStateException("Refresh source identity no longer matches selection");
        }
        return job;
    }

    private YagaRefreshSourceValidationService.Source source(YagaRefreshJob job) {
        return new YagaRefreshSourceValidationService.Source(job.getOldExternalListingId(),
                job.getOldShopSlug(), job.getOldProductSlug(), job.getOldPublicUrl());
    }

    private record SourcePreflight(Long accountId, Long productId, Long listingId, Long jobVersion,
                                   YagaRefreshSourceValidationService.Source source) { }

    private AutoStepFailure autoStepFailure(RuntimeException exception) {
        YagaPublishingFormDiagnostics publishingDiagnostics =
                publishingDiagnostics(exception);
        if (publishingDiagnostics != null &&
                publishingDiagnostics.safeErrorCode() != null) {
            String safeErrorCode =
                    "AUTO_" + publishingDiagnostics.safeErrorCode();
            return new AutoStepFailure(
                    safeErrorCode,
                    "Automatic Yaga refresh stopped during " +
                            safeStage(publishingDiagnostics.operationStage()),
                    publishingDiagnostics.operationStage(),
                    publishingDiagnostics.exceptionClass(),
                    publishingDiagnostics.rootCauseClass()
            );
        }

        if (exception instanceof YagaHidingAuthException hidingAuth) {
            return hidingFailure(exception, hidingAuth.getDetails(), "HIDING_AUTH_UNAVAILABLE");
        }
        if (exception instanceof YagaHidingPreconditionException precondition) {
            return hidingFailure(exception, precondition.getDetails(), "HIDE_TARGET_UNVERIFIED");
        }

        if (exception instanceof YagaPublishingDriveDownloadException) {
            String rootCauseClass = rootCauseClass(exception);
            boolean googleAuthFailure = TokenResponseException.class
                    .getName()
                    .equals(rootCauseClass);
            return new AutoStepFailure(
                    googleAuthFailure
                            ? "AUTO_GOOGLE_DRIVE_AUTH_INVALID_OR_EXPIRED"
                            : "AUTO_GOOGLE_DRIVE_DOWNLOAD_FAILED",
                    "Automatic Yaga refresh stopped during DOWNLOAD_IMAGES",
                    "DOWNLOAD_IMAGES",
                    exception.getClass().getName(),
                    rootCauseClass
            );
        }

        if (exception instanceof YagaRefreshInvalidStateException) {
            return new AutoStepFailure(
                    "AUTO_REFRESH_INVALID_STATE",
                    safeInvalidStateMessage(exception.getMessage()),
                    null,
                    exception.getClass().getName(),
                    rootCauseClass(exception)
            );
        }

        return new AutoStepFailure(
                "AUTO_REFRESH_STEP_FAILED",
                "Automatic Yaga refresh stopped after a safe execution error",
                null,
                exception.getClass().getName(),
                rootCauseClass(exception)
        );
    }

    private AutoStepFailure hidingFailure(RuntimeException exception, java.util.Map<String, String> details,
                                          String fallbackCode) {
        String stage = details.getOrDefault("operationStage", "PREPARE_HIDE");
        String code = details.getOrDefault("safeErrorCode", fallbackCode);
        String state = details.getOrDefault("pageState", "UNKNOWN");
        String summary = "Automatic Yaga refresh stopped during " + stage + ": " + code +
                "; pageState=" + state +
                "; visibleLoginCount=" + details.getOrDefault("visibleLoginCount", "0") +
                "; ownerControlsVisible=" + details.getOrDefault("ownerControlsVisible", "false") +
                "; candidateCount=" + details.getOrDefault("candidateCount", "0") +
                "; visibleCandidateCount=" + details.getOrDefault("visibleCandidateCount", "0");
        return new AutoStepFailure("AUTO_" + code, summary, stage,
                exception.getClass().getName(), rootCauseClass(exception));
    }

    private String safeInvalidStateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Automatic Yaga refresh stopped because the current refresh state is invalid";
        }
        return "Automatic Yaga refresh stopped because the current refresh state is invalid: " +
                message;
    }

    private YagaPublishingFormDiagnostics publishingDiagnostics(
            RuntimeException exception
    ) {
        if (exception instanceof YagaPublishingAuthException auth) {
            return auth.getDiagnostics();
        }
        if (exception instanceof YagaPublishingFormException form) {
            return form.getDiagnostics();
        }
        return null;
    }

    private String safeStage(String operationStage) {
        return operationStage == null || operationStage.isBlank()
                ? "Yaga automation"
                : operationStage;
    }

    private String rootCauseClass(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName();
    }

    private boolean autoPublish(YagaRefreshJob job) {
        log.info(
                "Yaga AUTO refresh job started: runId={} jobId={} oldListingId={}",
                job.getRun().getId(),
                job.getId(),
                job.getOldListing().getId()
        );
        YagaRefreshPublicationPreparationResponse preparation =
                publicationService.preparePublication(
                        job.getRun().getId(),
                        job.getId()
                );
        if (!publicationReady(preparation)) {
            markUnsafe(
                    job.getRun().getId(),
                    job.getId(),
                    "AUTO_PUBLICATION_NOT_READY",
                    "Automatic publication readiness could not be confirmed"
            );
            return false;
        }
        log.info(
                "Yaga AUTO refresh publication preparation ready: runId={} jobId={} preparationId={} candidateCount={}",
                job.getRun().getId(),
                job.getId(),
                preparation.publicationPreparationId(),
                preparation.readiness().candidateCount()
        );

        YagaRefreshPublicationResultResponse result =
                publicationService.confirmPublication(
                        job.getRun().getId(),
                        job.getId(),
                        new YagaRefreshPublicationConfirmRequest(
                                preparation.confirmationToken(),
                                "PUBLISH"
                        )
                );
        boolean confirmed = result.jobStatus() ==
                YagaRefreshJobStatus.NEW_LISTING_CONFIRMED &&
                result.newListingId() != null;
        if (!confirmed) {
            markUnsafe(
                    job.getRun().getId(),
                    job.getId(),
                    "AUTO_PUBLICATION_RESULT_UNSAFE",
                    "Automatic publication result could not be confirmed"
            );
        }
        if (confirmed) {
            log.info(
                    "Yaga AUTO refresh publication confirmed: runId={} jobId={} newListingId={} externalListingId={}",
                    job.getRun().getId(),
                    job.getId(),
                    result.newListingId(),
                    result.newExternalListingId()
            );
        }
        return confirmed;
    }

    private boolean autoHide(YagaRefreshJob job) {
        YagaRefreshHidingExecutionService hidingService =
                hidingServiceProvider.getIfAvailable();
        if (hidingService == null) {
            markUnsafe(
                    job.getRun().getId(),
                    job.getId(),
                    "AUTO_HIDING_UNAVAILABLE",
                    "Automatic hiding service is not available"
            );
            return false;
        }

        YagaRefreshHidePreparationResponse preparation =
                hidingService.prepare(job.getRun().getId(), job.getId());
        if (!hideReady(preparation)) {
            markUnsafe(
                    job.getRun().getId(),
                    job.getId(),
                    "AUTO_HIDE_NOT_READY",
                    "Automatic hide readiness could not be confirmed"
            );
            return false;
        }
        log.info(
                "Yaga AUTO refresh hide preparation ready: runId={} jobId={} preparationId={} oldListingId={} candidateCount={}",
                job.getRun().getId(),
                job.getId(),
                preparation.hidePreparationId(),
                job.getOldListing().getId(),
                preparation.readiness().candidateCount()
        );

        YagaRefreshHideResultResponse result = hidingService.confirm(
                job.getRun().getId(),
                job.getId(),
                new YagaRefreshHideConfirmRequest(
                        preparation.confirmationToken(),
                        "HIDE"
                )
        );
        boolean confirmed = result.jobStatus() == YagaRefreshJobStatus.COMPLETED;
        if (!confirmed) {
            markUnsafe(
                    job.getRun().getId(),
                    job.getId(),
                    "AUTO_HIDE_RESULT_UNSAFE",
                    "Automatic hide result could not be confirmed"
            );
        }
        if (confirmed) {
            log.info(
                    "Yaga AUTO refresh hide confirmed: runId={} jobId={} oldListingId={}",
                    job.getRun().getId(),
                    job.getId(),
                    job.getOldListing().getId()
            );
            log.info(
                    "Yaga AUTO refresh job completed: runId={} jobId={} oldListingId={} newListingId={}",
                    job.getRun().getId(),
                    job.getId(),
                    job.getOldListing().getId(),
                    result.newListingId()
            );
        }
        return confirmed;
    }

    private boolean publicationReady(
            YagaRefreshPublicationPreparationResponse response
    ) {
        YagaPublishReadinessResponse readiness = response == null
                ? null : response.readiness();
        return response != null &&
                response.publicationPreparationId() != null &&
                response.confirmationToken() != null &&
                readiness != null &&
                readiness.sessionStatus() ==
                        YagaPublicationStatus.AWAITING_CONFIRMATION &&
                readiness.formStillValid() &&
                readiness.readyForConfirmation() &&
                readiness.candidateCount() == 1 &&
                readiness.visibleCandidateCount() == 1 &&
                readiness.enabledCandidateCount() == 1;
    }

    private boolean hideReady(YagaRefreshHidePreparationResponse response) {
        YagaRefreshHideReadinessResponse readiness = response == null
                ? null : response.readiness();
        return response != null &&
                response.hidePreparationId() != null &&
                response.confirmationToken() != null &&
                response.hideStatus() ==
                        YagaRefreshHideStatus.AWAITING_CONFIRMATION &&
                readiness != null &&
                readiness.targetStillValid() &&
                readiness.readyForConfirmation() &&
                readiness.candidateCount() == 1 &&
                readiness.visibleCandidateCount() == 1 &&
                readiness.enabledCandidateCount() == 1;
    }

    private Optional<YagaRefreshJob> currentJob(java.util.UUID runId) {
        return transactionTemplate.execute(status ->
                runRepository.findForUpdateWithJobsById(runId)
                        .flatMap(run -> {
                            if (isTerminal(run.getStatus())) {
                                return Optional.empty();
                            }
                            return YagaRefreshSequencingGuard.currentJob(run);
                        })
        );
    }

    private YagaRefreshAutoRunResult stopped(java.util.UUID runId) {
        YagaRefreshRun run = runRepository.findWithJobsById(runId)
                .orElseThrow();
        return new YagaRefreshAutoRunResult(
                run.getId(),
                true,
                isTerminal(run.getStatus()),
                run.getStatus(),
                run.getLastSafeErrorMessage()
        );
    }

    private void markUnsafe(
            java.util.UUID runId,
            java.util.UUID jobId,
            String errorCode,
            String safeMessage
    ) {
        markUnsafe(runId, jobId, errorCode, safeMessage, null);
    }

    private void markUnsafe(
            java.util.UUID runId,
            java.util.UUID jobId,
            String errorCode,
            String safeMessage,
            String openFormDiagnostic
    ) {
        Boolean updated = transactionTemplate.execute(status -> {
            YagaRefreshRun run = runRepository
                    .findForUpdateWithJobsById(runId)
                    .orElseThrow();
            YagaRefreshJob job = run.getJobs().stream()
                    .filter(candidate -> candidate.getId().equals(jobId))
                    .findFirst()
                    .orElseThrow();

            if (job.getStatus() == YagaRefreshJobStatus.COMPLETED ||
                    isTerminal(run.getStatus())) {
                return false;
            }

            boolean possiblePublicationClick =
                    job.getPublicationConfirmStartedAt() != null &&
                            job.getStatus() ==
                                    YagaRefreshJobStatus.PUBLISHING;
            boolean possibleHideClick =
                    job.getHideConfirmStartedAt() != null &&
                            job.getStatus() ==
                                    YagaRefreshJobStatus.HIDING_OLD;
            if (possiblePublicationClick) {
                job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                job.setPublicationStatus(
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN.name()
                );
            } else if (possibleHideClick) {
                job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                job.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
            } else if (job.getStatus() == YagaRefreshJobStatus.RESULT_UNKNOWN) {
                if (job.getPublicationStatus() == null) {
                    job.setPublicationStatus(
                            YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN.name()
                    );
                }
            } else {
                job.setStatus(YagaRefreshJobStatus.FAILED);
            }

            Instant now = clock.instant();
            job.setLastErrorCode(errorCode);
            job.setLastSafeErrorMessage(safeMessage);
            if (openFormDiagnostic != null) {
                job.setOpenFormDiagnostic(openFormDiagnostic);
            }
            job.setCompletedAt(now);
            // The run cannot execute any later queued jobs. Keep a job selected
            // if persisted state suggests it may already have started.
            run.getJobs().stream()
                    .filter(candidate -> candidate.getSelectionOrder() > job.getSelectionOrder())
                    .forEach(candidate -> YagaSelectedJobLifecycle.finish(
                            candidate, YagaRefreshJobStatus.SKIPPED, now));
            run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
            run.setCompletedAt(now);
            run.setLastErrorCode(errorCode);
            run.setLastSafeErrorMessage(safeMessage);
            return true;
        });
        if (Boolean.TRUE.equals(updated)) {
            log.warn(
                    "Yaga AUTO refresh run stopped: runId={} jobId={} safeErrorCode={} safeMessage={}",
                    runId,
                    jobId,
                    errorCode,
                    safeMessage
            );
        }
    }

    private boolean isTerminal(YagaRefreshRunStatus status) {
        return status == YagaRefreshRunStatus.COMPLETED ||
                status == YagaRefreshRunStatus.COMPLETED_WITH_ERRORS ||
                status == YagaRefreshRunStatus.CANCELLED ||
                status == YagaRefreshRunStatus.DRY_RUN_COMPLETED ||
                status == YagaRefreshRunStatus.FAILED;
    }

    private record AutoStepFailure(
            String safeErrorCode,
            String safeMessage,
            String operationStage,
            String exceptionClass,
            String rootCauseClass
    ) {
    }
}
