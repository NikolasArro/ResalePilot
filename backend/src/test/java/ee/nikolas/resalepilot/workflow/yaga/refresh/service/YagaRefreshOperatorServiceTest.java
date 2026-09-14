package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorNextAction;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class YagaRefreshOperatorServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    private YagaRefreshRunRepository repository;
    private YagaRefreshExecutionService publicationService;
    private YagaRefreshHidingExecutionService hidingService;
    private YagaRefreshOperatorService service;
    private YagaRefreshRun run;

    @BeforeEach
    void setUp() {
        repository = mock(YagaRefreshRunRepository.class);
        publicationService = mock(YagaRefreshExecutionService.class);
        hidingService = mock(YagaRefreshHidingExecutionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaRefreshHidingExecutionService> hidingProvider =
                mock(ObjectProvider.class);
        when(hidingProvider.getIfAvailable()).thenReturn(hidingService);

        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new YagaRefreshOperatorService(
                repository, publicationService, hidingProvider,
                transactionManager
        );

        run = new YagaRefreshRun(
                YagaRefreshTriggerType.MANUAL,
                YagaRefreshRunMode.MANUAL,
                3,
                "operator-test",
                NOW
        );
        run.setId(UUID.randomUUID());
        run.setStatus(YagaRefreshRunStatus.AWAITING_CONFIRMATION);
        when(repository.findWithJobsById(run.getId()))
                .thenReturn(Optional.of(run));
        when(repository.findForUpdateWithJobsById(run.getId()))
                .thenReturn(Optional.of(run));
    }

    @Test
    void emptyRunHasNoCandidatesAndDoesNotDelegate() {
        var state = service.operatorState(run.getId());
        var prepared = service.prepareNext(run.getId());

        assertThat(state.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.NO_CANDIDATES);
        assertThat(prepared.nextAction()).isEqualTo(state.nextAction());
        assertThat(prepared.confirmationToken()).isNull();
        verify(publicationService, never()).preparePublication(any(), any());
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void selectedJobIsPreparedThroughExistingPublicationService() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.SELECTED);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job));

        var state = service.operatorState(run.getId());
        var response = service.prepareNext(run.getId());

        assertThat(state.nextAction()).isEqualTo(
                YagaRefreshOperatorNextAction.PREPARE_PUBLICATION);
        assertThat(state.requiresManualConfirmation()).isFalse();
        assertThat(state.currentJob().jobId()).isEqualTo(job.getId());
        assertThat(response.nextAction()).isEqualTo(
                YagaRefreshOperatorNextAction.CONFIRM_PUBLICATION);
        assertThat(response.confirmationToken()).isEqualTo("publish-once");
        assertThat(response.safeReadiness().targetStillValid()).isTrue();
        verify(publicationService).preparePublication(run.getId(), job.getId());
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void invalidPublicationPreparationDoesNotReturnConfirmPublication() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.SELECTED);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(invalidPublicationPreparation(job));

        var response = service.prepareNext(run.getId());

        assertThat(response.nextAction()).isEqualTo(
                YagaRefreshOperatorNextAction.PREPARE_PUBLICATION);
        assertThat(response.confirmationToken()).isNull();
        assertThat(response.preparationId()).isNull();
        assertThat(response.readyForConfirmation()).isFalse();
        assertThat(response.safeReadiness().targetStillValid()).isFalse();
        verify(publicationService).preparePublication(run.getId(), job.getId());
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void publicationAwaitingConfirmationRequiresManualConfirmation() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(UUID.randomUUID());
        job.setPublicationStatus("AWAITING_CONFIRMATION");

        var state = service.operatorState(run.getId());

        assertThat(state.nextAction()).isEqualTo(
                YagaRefreshOperatorNextAction.CONFIRM_PUBLICATION);
        assertThat(state.requiresManualConfirmation()).isTrue();
    }

    @Test
    void expiredPublicationPreparationIsRecoveredBeforeOperatorState() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(UUID.randomUUID());
        job.setPublicationStatus("AWAITING_CONFIRMATION");
        doAnswer(invocation -> {
            job.setStatus(YagaRefreshJobStatus.SELECTED);
            job.setPublicationPreparationId(null);
            job.setPublicationStatus(null);
            job.setPublicationPreparedAt(null);
            return true;
        }).when(publicationService)
                .recoverExpiredPublicationPreparation(run.getId());

        var state = service.operatorState(run.getId());

        assertThat(state.nextAction()).isEqualTo(
                YagaRefreshOperatorNextAction.PREPARE_PUBLICATION);
        assertThat(state.requiresManualConfirmation()).isFalse();
        assertThat(state.currentJob().jobStatus())
                .isEqualTo(YagaRefreshJobStatus.SELECTED);
        assertThat(state.currentJob().publicationStatus())
                .isEqualTo("NOT_STARTED");
        assertThat(job.getPublicationPreparationId()).isNull();
        verify(publicationService)
                .recoverExpiredPublicationPreparation(run.getId());
    }

    @Test
    void publicationUnknownOrRestartedConfirmRequiresReconciliation() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.RESULT_UNKNOWN);
        job.setPublicationPreparationId(UUID.randomUUID());
        job.setPublicationConfirmStartedAt(NOW);

        assertThat(service.operatorState(run.getId()).nextAction())
                .isEqualTo(
                        YagaRefreshOperatorNextAction.RECONCILE_PUBLICATION);
    }

    @Test
    void confirmedPublicationIsPreparedThroughExistingHidingService() {
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        YagaRefreshJob job = addJob(
                0, YagaRefreshJobStatus.NEW_LISTING_CONFIRMED
        );
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenReturn(hidePreparation(job));

        assertThat(service.operatorState(run.getId()).nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.PREPARE_HIDE);
        var response = service.prepareNext(run.getId());

        assertThat(response.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.CONFIRM_HIDE);
        assertThat(response.confirmationToken()).isEqualTo("hide-once");
        verify(hidingService).prepare(run.getId(), job.getId());
        verify(publicationService, never()).preparePublication(any(), any());
    }

    @Test
    void invalidHidePreparationDoesNotReturnConfirmHide() {
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        YagaRefreshJob job = addJob(
                0, YagaRefreshJobStatus.NEW_LISTING_CONFIRMED
        );
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenReturn(invalidHidePreparation(job));

        var response = service.prepareNext(run.getId());

        assertThat(response.stage())
                .isEqualTo(ee.nikolas.resalepilot.workflow.yaga.refresh.dto
                        .YagaRefreshOperatorStage.HIDING);
        assertThat(response.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.BLOCKED);
        assertThat(response.readyForConfirmation()).isFalse();
        assertThat(response.confirmationToken()).isNull();
        assertThat(response.preparationId()).isNull();
        assertThat(response.safeReadiness().targetStillValid()).isFalse();
        assertThat(response.safeReadiness().candidateCount()).isZero();
        assertThat(response.safeReadiness().operationStage())
                .isEqualTo("INSPECT_HIDE_TARGET");
        assertThat(response.safeReadiness().currentUrlHost())
                .isEqualTo("www.yaga.ee");
        assertThat(response.safeReadiness().currentUrlPath())
                .isEqualTo("/nik-ar/toode/old-1");
        assertThat(response.safeReadiness().expectedShopSlug())
                .isEqualTo("nik-ar");
        assertThat(response.safeReadiness().expectedProductSlug())
                .isEqualTo("old-1");
        assertThat(response.safeReadiness().targetUrlMatchesExpected())
                .isTrue();
        verify(hidingService).prepare(run.getId(), job.getId());
    }

    @Test
    void targetInvalidHideStateRequiresReviewInsteadOfConfirm() {
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        YagaRefreshJob job = addJob(
                0, YagaRefreshJobStatus.NEW_LISTING_CONFIRMED
        );
        job.setHideStatus(YagaRefreshHideStatus.TARGET_INVALID);
        job.setLastSafeErrorMessage(
                "Yaga hide button was not found for the old listing"
        );

        var state = service.operatorState(run.getId());

        assertThat(state.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.BLOCKED);
        assertThat(state.requiresManualConfirmation()).isFalse();
        assertThat(state.blockedReason())
                .isEqualTo("Yaga hide button was not found for the old listing");
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void hidingAwaitingConfirmationRequiresManualConfirmation() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.HIDING_OLD);
        job.setHidePreparationId(UUID.randomUUID());
        job.setHideStatus(YagaRefreshHideStatus.AWAITING_CONFIRMATION);

        var state = service.operatorState(run.getId());
        assertThat(state.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.CONFIRM_HIDE);
        assertThat(state.requiresManualConfirmation()).isTrue();
    }

    @Test
    void hidingUnknownOrConfirmingAfterRestartRequiresReconciliation() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.HIDING_OLD);
        job.setHidePreparationId(UUID.randomUUID());
        job.setHideStatus(YagaRefreshHideStatus.CONFIRMING);
        job.setHideConfirmStartedAt(NOW);

        assertThat(service.operatorState(run.getId()).nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.RECONCILE_HIDE);
        var response = service.prepareNext(run.getId());
        assertThat(response.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.RECONCILE_HIDE);
        assertThat(response.confirmationToken()).isNull();
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void completedJobsAreSkippedUsingSelectionOrderThenId() {
        YagaRefreshJob completed = addJob(0, YagaRefreshJobStatus.COMPLETED);
        YagaRefreshJob later = addJob(2, YagaRefreshJobStatus.SELECTED);
        YagaRefreshJob current = addJob(1, YagaRefreshJobStatus.SELECTED);

        var state = service.operatorState(run.getId());

        assertThat(state.currentJob().jobId()).isEqualTo(current.getId());
        assertThat(state.completedJobCount()).isEqualTo(1);
        assertThat(state.remainingJobCount()).isEqualTo(2);
        assertThat(state.currentJob().jobId()).isNotEqualTo(later.getId());
        assertThat(state.currentJob().jobId()).isNotEqualTo(completed.getId());
    }

    @Test
    void unknownCurrentJobBlocksFollowingJob() {
        YagaRefreshJob current = addJob(0, YagaRefreshJobStatus.RESULT_UNKNOWN);
        current.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
        YagaRefreshJob later = addJob(1, YagaRefreshJobStatus.SELECTED);

        var state = service.operatorState(run.getId());

        assertThat(state.currentJob().jobId()).isEqualTo(current.getId());
        assertThat(state.currentJob().jobId()).isNotEqualTo(later.getId());
        assertThat(state.nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.RECONCILE_HIDE);
    }

    @Test
    void completedAndCancelledRunsAreTerminalAndIdempotent() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.COMPLETED);
        run.setStatus(YagaRefreshRunStatus.COMPLETED);

        assertThat(service.prepareNext(run.getId()).nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.RUN_COMPLETED);
        run.setStatus(YagaRefreshRunStatus.CANCELLED);
        job.setStatus(YagaRefreshJobStatus.SELECTED);
        assertThat(service.prepareNext(run.getId()).nextAction())
                .isEqualTo(YagaRefreshOperatorNextAction.RUN_CANCELLED);
        verify(publicationService, never()).preparePublication(any(), any());
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void operatorStateNeverContainsConfirmationToken() {
        YagaRefreshJob job = addJob(0, YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(UUID.randomUUID());
        job.setPublicationStatus("AWAITING_CONFIRMATION");

        assertThat(service.operatorState(run.getId()).toString())
                .doesNotContain("publish-once", "hide-once", "token");
    }

    private YagaRefreshJob addJob(
            int order,
            YagaRefreshJobStatus status
    ) {
        long id = run.getJobs().size() + 1L;
        Product product = new Product("SKU-" + id, "Title " + id);
        product.setId(id);
        MarketplaceListing listing = new MarketplaceListing(
                product, Marketplace.YAGA, "old-" + id,
                "https://www.yaga.ee/nik-ar/toode/old-" + id
        );
        listing.setId(id);
        listing.setShopSlug("nik-ar");
        listing.setProductSlug("old-" + id);
        YagaRefreshJob job = new YagaRefreshJob(
                product, listing, listing.getExternalListingId(),
                listing.getShopSlug(), listing.getProductSlug(),
                listing.getExternalUrl(), product.getTitle(), NOW, NOW,
                1, 1, order, NOW
        );
        job.setId(UUID.randomUUID());
        job.setStatus(status);
        run.addJob(job);
        run.setSelectedJobCount(run.getJobs().size());
        return job;
    }

    private YagaRefreshPublicationPreparationResponse publicationPreparation(
            YagaRefreshJob job
    ) {
        UUID preparationId = UUID.randomUUID();
        YagaPublishReadinessResponse readiness =
                new YagaPublishReadinessResponse(
                        preparationId,
                        YagaPublicationStatus.AWAITING_CONFIRMATION,
                        null, true, 1, 1, 1,
                        null, null, null, true, NOW
                );
        return new YagaRefreshPublicationPreparationResponse(
                run.getId(), job.getId(), job.getProduct().getId(),
                job.getOldListing().getId(), YagaRefreshJobStatus.PUBLISHING,
                preparationId, "AWAITING_CONFIRMATION", "publish-once",
                NOW.plusSeconds(600), readiness
        );
    }

    private YagaRefreshPublicationPreparationResponse invalidPublicationPreparation(
            YagaRefreshJob job
    ) {
        UUID preparationId = UUID.randomUUID();
        YagaPublishReadinessResponse readiness =
                new YagaPublishReadinessResponse(
                        preparationId,
                        YagaPublicationStatus.AWAITING_CONFIRMATION,
                        null, false, 0, 0, 0,
                        null, null, null, false, NOW
                );
        return new YagaRefreshPublicationPreparationResponse(
                run.getId(), job.getId(), job.getProduct().getId(),
                job.getOldListing().getId(), YagaRefreshJobStatus.SELECTED,
                null, null, null, null, readiness
        );
    }

    private YagaRefreshHidePreparationResponse hidePreparation(
            YagaRefreshJob job
    ) {
        UUID preparationId = UUID.randomUUID();
        YagaRefreshHideReadinessResponse readiness =
                new YagaRefreshHideReadinessResponse(
                        preparationId, YagaHidingStatus.AWAITING_CONFIRMATION,
                        true, 1, 1, 1, true, NOW,
                        "INSPECT_HIDE_TARGET", "www.yaga.ee",
                        "/nik-ar/toode/old-1", "nik-ar", "old-1", true
                );
        return new YagaRefreshHidePreparationResponse(
                run.getId(), job.getId(), job.getProduct().getId(),
                job.getOldListing().getId(), 99L,
                YagaRefreshJobStatus.HIDING_OLD, preparationId,
                YagaRefreshHideStatus.AWAITING_CONFIRMATION, "hide-once",
                NOW.plusSeconds(600), readiness
        );
    }

    private YagaRefreshHidePreparationResponse invalidHidePreparation(
            YagaRefreshJob job
    ) {
        UUID preparationId = UUID.randomUUID();
        YagaRefreshHideReadinessResponse readiness =
                new YagaRefreshHideReadinessResponse(
                        preparationId, YagaHidingStatus.AWAITING_CONFIRMATION,
                        false, 0, 0, 0, false, NOW,
                        "INSPECT_HIDE_TARGET", "www.yaga.ee",
                        "/nik-ar/toode/old-1", "nik-ar", "old-1", true
                );
        return new YagaRefreshHidePreparationResponse(
                run.getId(), job.getId(), job.getProduct().getId(),
                job.getOldListing().getId(), 99L,
                YagaRefreshJobStatus.NEW_LISTING_CONFIRMED, null,
                YagaRefreshHideStatus.TARGET_INVALID, null,
                null, readiness
        );
    }
}
