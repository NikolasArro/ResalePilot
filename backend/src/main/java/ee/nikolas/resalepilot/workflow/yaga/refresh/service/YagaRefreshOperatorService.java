package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorJobResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorNextAction;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorStage;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorStateResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPrepareNextResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRunNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.operator-enabled:false}' == 'true'"
)
public class YagaRefreshOperatorService {

    private static final String NOT_STARTED = "NOT_STARTED";

    private final YagaRefreshRunRepository runRepository;
    private final YagaRefreshExecutionService publicationService;
    private final ObjectProvider<YagaRefreshHidingExecutionService>
            hidingServiceProvider;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;

    public YagaRefreshOperatorService(
            YagaRefreshRunRepository runRepository,
            YagaRefreshExecutionService publicationService,
            ObjectProvider<YagaRefreshHidingExecutionService>
                    hidingServiceProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.publicationService = publicationService;
        this.hidingServiceProvider = hidingServiceProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate =
                new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    public YagaRefreshOperatorStateResponse operatorState(UUID runId) {
        publicationService.recoverExpiredPublicationPreparation(runId);
        return readOnlyTransactionTemplate.execute(status -> state(
                runRepository.findWithJobsById(runId)
                        .orElseThrow(() ->
                                new YagaRefreshRunNotFoundException(runId))
        ));
    }

    public YagaRefreshPrepareNextResponse prepareNext(UUID runId) {
        publicationService.recoverExpiredPublicationPreparation(runId);
        YagaRefreshOperatorStateResponse state = transactionTemplate.execute(
                status -> state(runRepository.findForUpdateWithJobsById(runId)
                        .orElseThrow(() ->
                                new YagaRefreshRunNotFoundException(runId)))
        );
        if (state == null) {
            throw new IllegalStateException(
                    "Yaga refresh operator state transaction returned no result"
            );
        }

        return switch (state.nextAction()) {
            case PREPARE_PUBLICATION,
                 CHECK_PUBLICATION_READINESS,
                 CONFIRM_PUBLICATION -> preparePublication(state);
            case PREPARE_HIDE,
                 CHECK_HIDE_READINESS,
                 CONFIRM_HIDE -> prepareHiding(state);
            default -> terminalResponse(state);
        };
    }

    private YagaRefreshPrepareNextResponse preparePublication(
            YagaRefreshOperatorStateResponse state
    ) {
        YagaRefreshPublicationPreparationResponse response =
                publicationService.preparePublication(
                        state.runId(), state.currentJob().jobId()
                );
        YagaRefreshOperatorReadinessResponse readiness =
                safeReadiness(response.readiness());
        if (response.confirmationToken() == null ||
                response.publicationPreparationId() == null ||
                response.readiness() == null ||
                !response.readiness().readyForConfirmation()) {
            return new YagaRefreshPrepareNextResponse(
                    state.runId(), response.jobId(),
                    YagaRefreshOperatorStage.PUBLICATION,
                    YagaRefreshOperatorNextAction.PREPARE_PUBLICATION,
                    response.publicationPreparationId(),
                    null, response.expiresAt(),
                    false,
                    readiness
            );
        }
        return new YagaRefreshPrepareNextResponse(
                state.runId(), response.jobId(),
                YagaRefreshOperatorStage.PUBLICATION,
                YagaRefreshOperatorNextAction.CONFIRM_PUBLICATION,
                response.publicationPreparationId(),
                response.confirmationToken(), response.expiresAt(),
                response.readiness().readyForConfirmation(),
                readiness
        );
    }

    private YagaRefreshPrepareNextResponse prepareHiding(
            YagaRefreshOperatorStateResponse state
    ) {
        YagaRefreshHidingExecutionService hidingService =
                hidingServiceProvider.getIfAvailable();
        if (hidingService == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh hiding execution is not enabled"
            );
        }
        YagaRefreshHidePreparationResponse response = hidingService.prepare(
                state.runId(), state.currentJob().jobId()
        );
        YagaRefreshOperatorReadinessResponse readiness =
                new YagaRefreshOperatorReadinessResponse(
                        response.readiness().sessionStatus().name(),
                        response.readiness().targetStillValid(),
                        response.readiness().candidateCount(),
                        response.readiness().visibleCandidateCount(),
                        response.readiness().enabledCandidateCount(),
                        response.readiness().readyForConfirmation(),
                        response.readiness().inspectedAt(),
                        response.readiness().operationStage(),
                        response.readiness().currentUrlHost(),
                        response.readiness().currentUrlPath(),
                        response.readiness().expectedShopSlug(),
                        response.readiness().expectedProductSlug(),
                        response.readiness().targetUrlMatchesExpected()
                );
        if (!response.readiness().readyForConfirmation() ||
                !response.readiness().targetStillValid() ||
                response.confirmationToken() == null ||
                response.hidePreparationId() == null) {
            return new YagaRefreshPrepareNextResponse(
                    state.runId(), response.jobId(),
                    YagaRefreshOperatorStage.HIDING,
                    YagaRefreshOperatorNextAction.BLOCKED,
                    response.hidePreparationId(), null, response.expiresAt(),
                    false, readiness
            );
        }
        return new YagaRefreshPrepareNextResponse(
                state.runId(), response.jobId(),
                YagaRefreshOperatorStage.HIDING,
                YagaRefreshOperatorNextAction.CONFIRM_HIDE,
                response.hidePreparationId(), response.confirmationToken(),
                response.expiresAt(),
                true, readiness
        );
    }

    private YagaRefreshPrepareNextResponse terminalResponse(
            YagaRefreshOperatorStateResponse state
    ) {
        UUID jobId = state.currentJob() == null
                ? null : state.currentJob().jobId();
        return new YagaRefreshPrepareNextResponse(
                state.runId(), jobId, stageFor(state.nextAction()),
                state.nextAction(), null, null, null, false, null
        );
    }

    private YagaRefreshOperatorStateResponse state(YagaRefreshRun run) {
        int completed = (int) run.getJobs().stream()
                .filter(job -> job.getStatus() == YagaRefreshJobStatus.COMPLETED)
                .count();
        int selected = run.getSelectedJobCount();

        if (run.getJobs().isEmpty()) {
            return response(run, selected, completed, null,
                    YagaRefreshOperatorNextAction.NO_CANDIDATES,
                    false, null);
        }
        if (run.getStatus() == YagaRefreshRunStatus.CANCELLED) {
            return response(run, selected, completed, null,
                    YagaRefreshOperatorNextAction.RUN_CANCELLED,
                    false, null);
        }
        if (run.getStatus() == YagaRefreshRunStatus.COMPLETED) {
            return response(run, selected, completed, null,
                    YagaRefreshOperatorNextAction.RUN_COMPLETED,
                    false, null);
        }
        if (run.getMode() != YagaRefreshRunMode.MANUAL) {
            return response(run, selected, completed, null,
                    YagaRefreshOperatorNextAction.BLOCKED,
                    false, "Refresh run is not a manual execution run");
        }

        YagaRefreshJob current = YagaRefreshSequencingGuard.currentJob(run)
                .orElse(null);
        if (current == null) {
            return response(run, selected, completed, null,
                    YagaRefreshOperatorNextAction.RUN_COMPLETED,
                    false, null);
        }
        Action action = action(current);
        return response(run, selected, completed, current,
                action.nextAction(), action.manualConfirmation(),
                action.blockedReason());
    }

    private Action action(YagaRefreshJob job) {
        return switch (job.getStatus()) {
            case SELECTED -> Action.of(
                    YagaRefreshOperatorNextAction.PREPARE_PUBLICATION);
            case PUBLISHING -> publicationAction(job);
            case NEW_LISTING_CONFIRMED -> newListingConfirmedAction(job);
            case HIDING_OLD -> hidingAction(job);
            case RESULT_UNKNOWN -> unknownAction(job);
            case FAILED -> Action.blocked(
                    safeBlockedReason(job, "Current refresh job failed"));
            case DRY_RUN_COMPLETED -> Action.blocked(
                    "Current job is not executable");
            case COMPLETED -> throw new IllegalStateException(
                    "Completed job cannot be current"
            );
        };
    }

    private Action publicationAction(YagaRefreshJob job) {
        if (job.getPublicationConfirmStartedAt() != null ||
                matches(job.getPublicationStatus(), "PUBLISHING",
                        "PUBLISH_RESULT_UNKNOWN",
                        "PUBLISHED_DB_SYNC_FAILED")) {
            return Action.of(
                    YagaRefreshOperatorNextAction.RECONCILE_PUBLICATION);
        }
        if ("AWAITING_CONFIRMATION".equals(job.getPublicationStatus())) {
            return Action.manual(
                    YagaRefreshOperatorNextAction.CONFIRM_PUBLICATION);
        }
        if (job.getPublicationPreparationId() != null) {
            return Action.of(
                    YagaRefreshOperatorNextAction.CHECK_PUBLICATION_READINESS);
        }
        if (job.getPublicationStatus() == null ||
                "PREPARING".equals(job.getPublicationStatus())) {
            return Action.of(
                    YagaRefreshOperatorNextAction.PREPARE_PUBLICATION);
        }
        return Action.blocked("Publication state requires operator review");
    }

    private Action newListingConfirmedAction(YagaRefreshJob job) {
        if (job.getHideStatus() == YagaRefreshHideStatus.TARGET_INVALID) {
            return Action.blocked(safeBlockedReason(
                    job, "Hide target requires operator review"
            ));
        }
        return Action.of(YagaRefreshOperatorNextAction.PREPARE_HIDE);
    }

    private Action hidingAction(YagaRefreshJob job) {
        if (job.getHideConfirmStartedAt() != null ||
                job.getHideStatus() == YagaRefreshHideStatus.CONFIRMING ||
                job.getHideStatus() == YagaRefreshHideStatus.RESULT_UNKNOWN) {
            return Action.of(YagaRefreshOperatorNextAction.RECONCILE_HIDE);
        }
        if (job.getHideStatus() == YagaRefreshHideStatus.TARGET_INVALID) {
            return Action.blocked(safeBlockedReason(
                    job, "Hide target requires operator review"
            ));
        }
        if (job.getHideStatus() ==
                YagaRefreshHideStatus.AWAITING_CONFIRMATION) {
            return Action.manual(YagaRefreshOperatorNextAction.CONFIRM_HIDE);
        }
        if (job.getHidePreparationId() != null) {
            return Action.of(
                    YagaRefreshOperatorNextAction.CHECK_HIDE_READINESS);
        }
        return Action.of(YagaRefreshOperatorNextAction.PREPARE_HIDE);
    }

    private Action unknownAction(YagaRefreshJob job) {
        if (job.getHideConfirmStartedAt() != null ||
                job.getHideStatus() == YagaRefreshHideStatus.RESULT_UNKNOWN ||
                job.getHideStatus() == YagaRefreshHideStatus.CONFIRMING) {
            return Action.of(YagaRefreshOperatorNextAction.RECONCILE_HIDE);
        }
        if (job.getPublicationConfirmStartedAt() != null ||
                job.getPublicationPreparationId() != null) {
            return Action.of(
                    YagaRefreshOperatorNextAction.RECONCILE_PUBLICATION);
        }
        return Action.blocked(safeBlockedReason(
                job, "Current refresh result is unknown"
        ));
    }

    private YagaRefreshOperatorStateResponse response(
            YagaRefreshRun run,
            int selected,
            int completed,
            YagaRefreshJob current,
            YagaRefreshOperatorNextAction nextAction,
            boolean manual,
            String blockedReason
    ) {
        return new YagaRefreshOperatorStateResponse(
                run.getId(), run.getStatus(), selected, completed,
                selected - completed,
                current == null ? null : operatorJob(current),
                nextAction, manual, blockedReason
        );
    }

    private YagaRefreshOperatorJobResponse operatorJob(YagaRefreshJob job) {
        return new YagaRefreshOperatorJobResponse(
                job.getId(), job.getSelectionOrder(),
                job.getProduct().getId(), job.getProductTitle() == null
                        ? job.getProduct().getTitle() : job.getProductTitle(),
                job.getOldListing().getId(), job.getOldPublicUrl() == null
                        ? job.getOldListing().getExternalUrl()
                        : job.getOldPublicUrl(),
                job.getNewListing() == null
                        ? null : job.getNewListing().getId(),
                job.getNewProductUrl() == null && job.getNewListing() != null
                        ? job.getNewListing().getExternalUrl()
                        : job.getNewProductUrl(),
                job.getStatus(),
                job.getPublicationStatus() == null
                        ? NOT_STARTED : job.getPublicationStatus(),
                job.getHideStatus() == null
                        ? YagaRefreshHideStatus.NOT_STARTED
                        : job.getHideStatus()
        );
    }

    private YagaRefreshOperatorReadinessResponse safeReadiness(
            YagaPublishReadinessResponse readiness
    ) {
        return new YagaRefreshOperatorReadinessResponse(
                readiness.sessionStatus().name(), readiness.formStillValid(),
                readiness.candidateCount(), readiness.visibleCandidateCount(),
                readiness.enabledCandidateCount(),
                readiness.readyForConfirmation(), readiness.inspectedAt(),
                null, null, null, null, null, false
        );
    }

    private boolean matches(String value, String... expected) {
        if (value == null) {
            return false;
        }
        for (String candidate : expected) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private String safeBlockedReason(YagaRefreshJob job, String fallback) {
        return job.getLastSafeErrorMessage() == null
                ? fallback : job.getLastSafeErrorMessage();
    }

    private YagaRefreshOperatorStage stageFor(
            YagaRefreshOperatorNextAction action
    ) {
        return switch (action) {
            case PREPARE_PUBLICATION, CHECK_PUBLICATION_READINESS,
                 CONFIRM_PUBLICATION, RECONCILE_PUBLICATION ->
                    YagaRefreshOperatorStage.PUBLICATION;
            case PREPARE_HIDE, CHECK_HIDE_READINESS, CONFIRM_HIDE,
                 RECONCILE_HIDE -> YagaRefreshOperatorStage.HIDING;
            default -> null;
        };
    }

    private record Action(
            YagaRefreshOperatorNextAction nextAction,
            boolean manualConfirmation,
            String blockedReason
    ) {
        private static Action of(YagaRefreshOperatorNextAction action) {
            return new Action(action, false, null);
        }

        private static Action manual(YagaRefreshOperatorNextAction action) {
            return new Action(action, true, null);
        }

        private static Action blocked(String reason) {
            return new Action(
                    YagaRefreshOperatorNextAction.BLOCKED, false, reason
            );
        }
    }
}
