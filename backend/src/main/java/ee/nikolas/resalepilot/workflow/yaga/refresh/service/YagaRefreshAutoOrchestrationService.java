package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
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
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnExpression(
        "'${resalepilot.yaga.refresh.scheduler.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.hide-execution-enabled:false}' == 'true'"
)
public class YagaRefreshAutoOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(
            YagaRefreshAutoOrchestrationService.class
    );

    private final YagaRefreshSchedulerProperties properties;
    private final YagaRefreshRunService runService;
    private final YagaRefreshExecutionService publicationService;
    private final ObjectProvider<YagaRefreshHidingExecutionService>
            hidingServiceProvider;
    private final YagaRefreshRunRepository runRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public YagaRefreshAutoOrchestrationService(
            YagaRefreshSchedulerProperties properties,
            YagaRefreshRunService runService,
            YagaRefreshExecutionService publicationService,
            ObjectProvider<YagaRefreshHidingExecutionService>
                    hidingServiceProvider,
            YagaRefreshRunRepository runRepository,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.properties = properties;
        this.runService = runService;
        this.publicationService = publicationService;
        this.hidingServiceProvider = hidingServiceProvider;
        this.runRepository = runRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
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
            Optional<YagaRefreshRunResponse> created =
                    runService.startScheduledAutoRun(
                            idempotencyKey,
                            properties.batchSize()
                    );
            if (created.isEmpty()) {
                log.info(
                        "Yaga AUTO refresh scheduler skipped: reason={}",
                        "active-run-exists"
                );
                return YagaRefreshAutoRunResult.skipped(
                        "An active Yaga refresh run is already processing"
                );
            }

            YagaRefreshRunResponse run = created.get();
            log.info(
                    "Yaga AUTO refresh run created: runId={} selectedJobCount={}",
                    run.runId(),
                    run.selectedJobCount()
            );
            return processRun(run.runId());
        } finally {
            running.set(false);
        }
    }

    YagaRefreshAutoRunResult processRun(java.util.UUID runId) {
        while (true) {
            YagaRefreshJob job = currentJob(runId).orElse(null);
            if (job == null) {
                YagaRefreshRun run = runRepository.findWithJobsById(runId)
                        .orElseThrow();
                if (run.getStatus() == YagaRefreshRunStatus.COMPLETED) {
                    log.info(
                            "Yaga AUTO refresh run completed: runId={} selectedJobCount={}",
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
                markUnsafe(
                        runId,
                        job.getId(),
                        "AUTO_REFRESH_STEP_FAILED",
                        "Automatic Yaga refresh stopped after a safe execution error"
                );
                return stopped(runId);
            }
        }
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
            job.setCompletedAt(now);
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
}
