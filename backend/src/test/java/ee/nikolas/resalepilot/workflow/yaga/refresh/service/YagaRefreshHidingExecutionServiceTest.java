package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.common.dto.ApiErrorResponse;
import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationForbiddenException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.YagaHidingSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshHideFinalizationException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YagaRefreshHidingExecutionServiceTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID preparationId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-12T12:00:00Z"),
            ZoneOffset.UTC
    );

    private YagaRefreshRunRepository runRepository;
    private MarketplaceListingRepository listingRepository;
    private YagaHidingSessionManager manager;
    private YagaPageDataClient pageDataClient;
    private YagaRefreshHidingExecutionService service;
    private Product product;
    private MarketplaceListing oldListing;
    private MarketplaceListing newListing;
    private YagaRefreshRun run;
    private YagaRefreshJob job;

    @BeforeEach
    void setUp() {
        runRepository = mock(YagaRefreshRunRepository.class);
        listingRepository = mock(MarketplaceListingRepository.class);
        manager = mock(YagaHidingSessionManager.class);
        pageDataClient = mock(YagaPageDataClient.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaHidingSessionManager> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);

        service = new YagaRefreshHidingExecutionService(
                runRepository,
                listingRepository,
                provider,
                pageDataClient,
                transactionManager,
                clock
        );

        product = new Product("SKU-1", "Title");
        product.setId(10L);
        oldListing = listing(11L, product, "11", "old-slug", true);
        newListing = listing(12L, product, "12", "new-slug", false);
        run = new YagaRefreshRun(
                YagaRefreshTriggerType.MANUAL,
                YagaRefreshRunMode.MANUAL,
                1,
                "hide-test",
                clock.instant()
        );
        run.setId(runId);
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        job = new YagaRefreshJob(
                product,
                oldListing,
                oldListing.getExternalListingId(),
                oldListing.getShopSlug(),
                oldListing.getProductSlug(),
                oldListing.getExternalUrl(),
                product.getTitle(),
                oldListing.getExternalCreatedAt(),
                oldListing.getCreatedAt(),
                0,
                0,
                0,
                clock.instant()
        );
        job.setId(jobId);
        job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        job.setNewListing(newListing);
        job.setNewExternalListingId(newListing.getExternalListingId());
        job.setNewShopSlug(newListing.getShopSlug());
        job.setNewProductSlug(newListing.getProductSlug());
        job.setNewProductUrl(newListing.getExternalUrl());
        run.addJob(job);

        when(runRepository.findForUpdateWithJobsById(runId))
                .thenReturn(Optional.of(run));
        when(listingRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findByIdForUpdate(12L))
                .thenReturn(Optional.of(newListing));
        when(manager.prepare(11L)).thenReturn(preparation());
        when(manager.readiness(preparationId)).thenReturn(readiness());
    }

    @Test
    void preparePersistsOnlyUsableSessionAndDoesNotConfirm() {
        var response = service.prepare(runId, jobId);

        assertThat(response.confirmationToken()).isEqualTo("one-time-token");
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.HIDING_OLD);
        assertThat(job.getHidePreparationId()).isEqualTo(preparationId);
        assertThat(job.getHideStatus())
                .isEqualTo(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
        verify(manager, never()).confirmForRefresh(any(), any());
    }

    @Test
    void preparationRequiresConfirmedNewListing() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        assertThatThrownBy(() -> service.prepare(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
        verify(manager, never()).prepare(any());
    }

    @Test
    void differentProductBlocksPreparation() {
        Product other = new Product("OTHER", "Other");
        other.setId(99L);
        newListing.setProduct(other);
        assertBlockedPreparation();
    }

    @Test
    void snapshotMismatchBlocksPreparation() {
        job.setOldExternalListingId("changed");
        assertBlockedPreparation();
    }

    @Test
    void sameExternalIdentityBlocksPreparation() {
        newListing.setExternalListingId("11");
        job.setNewExternalListingId("11");
        assertBlockedPreparation();
    }

    @Test
    void nonCurrentOldListingBlocksPreparation() {
        oldListing.setCurrent(false);
        assertBlockedPreparation();
    }

    @Test
    void hiddenOrDeletedOldListingBlocksPreparation() {
        oldListing.setHiddenAt(clock.instant());
        assertBlockedPreparation();
        oldListing.setHiddenAt(null);
        oldListing.setDeletedAt(clock.instant());
        assertBlockedPreparation();
    }

    @Test
    void nonPublishedNewListingBlocksPreparation() {
        newListing.setStatus(MarketplaceListingStatus.HIDDEN);
        assertBlockedPreparation();
    }

    @Test
    void readinessIsReadOnly() {
        markAwaiting();
        var response = service.readiness(runId, jobId);
        assertThat(response.readyForConfirmation()).isTrue();
        verify(manager).readiness(preparationId);
        verify(manager, never()).confirmForRefresh(any(), any());
    }

    @Test
    void activePreparationIsReusedWithoutTokenOrSecondSession() {
        markAwaiting();
        job.setLastErrorCode("INVALID_HIDE_CONFIRMATION");
        job.setLastSafeErrorMessage("old error");
        when(manager.status(preparationId)).thenReturn(status(
                YagaHidingStatus.AWAITING_CONFIRMATION
        ));
        var response = service.prepare(runId, jobId);
        assertThat(response.confirmationToken()).isNull();
        assertThat(job.getLastErrorCode()).isNull();
        assertThat(job.getLastSafeErrorMessage()).isNull();
        verify(manager, never()).prepare(any());
    }

    @Test
    void expiredPreparationCanBeRecreated() {
        markAwaiting();
        when(manager.status(preparationId))
                .thenReturn(status(YagaHidingStatus.EXPIRED));
        var response = service.prepare(runId, jobId);
        assertThat(response.hidePreparationId()).isEqualTo(preparationId);
        verify(manager).prepare(11L);
    }

    @Test
    void missingInMemorySessionBeforeConfirmCanBeRecreated() {
        markAwaiting();
        when(manager.status(preparationId)).thenThrow(
                new YagaPublicationPreparationNotFoundException(preparationId)
        );
        service.prepare(runId, jobId);
        verify(manager).prepare(11L);
    }

    @Test
    void missingSessionAfterConfirmStartedBecomesUnknown() {
        markAwaiting();
        job.setHideConfirmStartedAt(clock.instant());
        when(manager.status(preparationId)).thenThrow(
                new YagaPublicationPreparationNotFoundException(preparationId)
        );
        assertThatThrownBy(() -> service.prepare(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        verify(manager, never()).prepare(any());
    }

    @Test
    void confirmRequiresExactPhraseBeforeSessionCall() {
        markAwaiting();
        assertThatThrownBy(() -> service.confirm(
                runId, jobId,
                new YagaRefreshHideConfirmRequest("token", "hide")
        )).isInstanceOf(YagaRefreshRequestInvalidException.class);
        verify(manager, never()).confirmForRefresh(any(), any());
    }

    @Test
    void invalidTokenIsSafeToRetryAndDoesNotClickAgainHere() {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any()))
                .thenThrow(new YagaPublicationForbiddenException());
        assertThatThrownBy(() -> service.confirm(
                runId, jobId,
                new YagaRefreshHideConfirmRequest("wrong", "HIDE")
        )).isInstanceOf(YagaRefreshRequestInvalidException.class);
        assertThat(job.getHideConfirmStartedAt()).isNull();
        assertThat(job.getHideStatus())
                .isEqualTo(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void successfulConfirmAtomicallySwitchesListingsAndCompletesRun() {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDDEN, true)
        );
        var response = service.confirm(
                runId, jobId,
                new YagaRefreshHideConfirmRequest("token", "HIDE")
        );

        assertThat(response.jobStatus()).isEqualTo(YagaRefreshJobStatus.COMPLETED);
        assertThat(oldListing.getStatus()).isEqualTo(MarketplaceListingStatus.HIDDEN);
        assertThat(oldListing.isCurrent()).isFalse();
        assertThat(oldListing.getDeletedAt()).isNull();
        assertThat(newListing.getStatus()).isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(newListing.isCurrent()).isTrue();
        assertThat(run.getStatus()).isEqualTo(YagaRefreshRunStatus.COMPLETED);
        verify(manager, times(1)).confirmForRefresh(any(), any());
    }

    @Test
    void repeatedConfirmedRequestDoesNotInvokeSecondClickPath() {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDDEN, true)
        );
        var request = new YagaRefreshHideConfirmRequest("token", "HIDE");
        service.confirm(runId, jobId, request);
        service.confirm(runId, jobId, request);
        verify(manager, times(1)).confirmForRefresh(any(), any());
    }

    @Test
    void databaseFailureAfterClickBecomesUnknownAndCannotClickAgain() {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDDEN, true)
        );
        when(listingRepository.saveAndFlush(oldListing)).thenThrow(
                new DataIntegrityViolationException(
                        "uq_marketplace_current_product"
                )
        );
        YagaRefreshHideConfirmRequest request =
                new YagaRefreshHideConfirmRequest("token", "HIDE");

        assertThatThrownBy(() -> service.confirm(runId, jobId, request))
                .isInstanceOf(YagaRefreshHideFinalizationException.class)
                .hasMessageNotContaining("uq_marketplace_current_product");
        assertThat(job.getStatus())
                .isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        assertThat(job.getHideStatus())
                .isEqualTo(YagaRefreshHideStatus.RESULT_UNKNOWN);
        assertThat(job.getLastErrorCode())
                .isEqualTo("DATABASE_FINALIZATION_FAILED");

        assertThatThrownBy(() -> service.confirm(runId, jobId, request))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
        verify(manager, times(1)).confirmForRefresh(any(), any());
    }

    @Test
    void ambiguousResultPersistsUnknownWithoutSwitchingListings() {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDE_RESULT_UNKNOWN, false)
        );
        service.confirm(
                runId, jobId,
                new YagaRefreshHideConfirmRequest("token", "HIDE")
        );
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        assertThat(job.getLastErrorCode()).isEqualTo("HIDE_RESULT_UNKNOWN");
        assertThat(oldListing.isCurrent()).isTrue();
        assertThat(newListing.isCurrent()).isFalse();
    }

    @Test
    void reconciliationConfirmsBothRemoteStatesWithoutClick() {
        markUnknown();
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(data(11L, "old-slug", "hidden"));
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(data(12L, "new-slug", "published"));
        var response = service.reconcile(runId, jobId);
        assertThat(response.jobStatus()).isEqualTo(YagaRefreshJobStatus.COMPLETED);
        verify(manager, never()).confirmForRefresh(any(), any());
    }

    @Test
    void persistedConfirmingJobCanBeReconciledWithoutSessionManager() {
        markAwaiting();
        job.setHideStatus(YagaRefreshHideStatus.CONFIRMING);
        job.setHideConfirmStartedAt(clock.instant());
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(data(11L, "old-slug", "hidden"));
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(data(12L, "new-slug", "published"));

        assertThatThrownBy(() -> service.confirm(
                runId,
                jobId,
                new YagaRefreshHideConfirmRequest("token", "HIDE")
        )).isInstanceOf(YagaRefreshInvalidStateException.class);

        var response = service.reconcile(runId, jobId);

        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.COMPLETED);
        verify(manager, never()).confirmForRefresh(any(), any());
        verify(manager, never()).prepare(any());
    }

    @Test
    void finalizationExceptionHasControlledSafeHttpMapping() {
        var response = new GlobalExceptionHandler()
                .handleYagaRefreshHideFinalization(
                        new YagaRefreshHideFinalizationException()
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo(
                "Yaga hide was confirmed, but local finalization requires reconciliation"
        );
        assertThat(body.message()).doesNotContain("SQL", "constraint", "33", "34");
    }

    @Test
    void reconciliationSoldOldListingRemainsUnknown() {
        markUnknown();
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(data(11L, "old-slug", "sold"));
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(data(12L, "new-slug", "published"));
        service.reconcile(runId, jobId);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        assertThat(oldListing.getStatus()).isEqualTo(MarketplaceListingStatus.PUBLISHED);
    }

    @Test
    void preparationIdentityMismatchIsCancelledWithoutPersistence() {
        YagaHidePreparationResponse wrong = preparation();
        wrong = new YagaHidePreparationResponse(
                wrong.preparationId(), 999L, wrong.newListingId(),
                wrong.productId(), wrong.oldExternalListingId(),
                wrong.oldProductSlug(), wrong.newExternalListingId(),
                wrong.newProductSlug(), wrong.currentUrl(),
                wrong.candidateCount(), wrong.visibleCandidateCount(),
                wrong.enabledCandidateCount(), wrong.controlText(),
                wrong.accessibleName(), wrong.tagName(), wrong.typeAttribute(),
                wrong.readyForConfirmation(), wrong.confirmationToken(),
                wrong.expiresAt(), wrong.status()
        );
        when(manager.prepare(11L)).thenReturn(wrong);
        assertThatThrownBy(() -> service.prepare(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
        assertThat(job.getHidePreparationId()).isNull();
        verify(manager).cancel(preparationId);
    }

    @Test
    void unrelatedListingIsNeverMutated() {
        Product other = new Product("OTHER", "Other");
        other.setId(90L);
        MarketplaceListing unrelated =
                listing(91L, other, "other-id", "other-slug", true);
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDDEN, true)
        );
        service.confirm(
                runId, jobId,
                new YagaRefreshHideConfirmRequest("token", "HIDE")
        );
        assertThat(unrelated.getStatus()).isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(unrelated.isCurrent()).isTrue();
    }

    @Test
    void concurrentPrepareCreatesOnlyOneBrowserSession() throws Exception {
        when(manager.status(preparationId)).thenReturn(status(
                YagaHidingStatus.AWAITING_CONFIRMATION
        ));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<UUID>> calls = List.of(
                    () -> service.prepare(runId, jobId).hidePreparationId(),
                    () -> service.prepare(runId, jobId).hidePreparationId()
            );
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            assertThat(results.get(0).get()).isEqualTo(preparationId);
            assertThat(results.get(1).get()).isEqualTo(preparationId);
        }
        verify(manager, times(1)).prepare(11L);
    }

    @Test
    void concurrentConfirmEntersClickPathOnlyOnce() throws Exception {
        markAwaiting();
        when(manager.confirmForRefresh(any(), any())).thenReturn(
                confirm(YagaHidingStatus.HIDDEN, true)
        );
        YagaRefreshHideConfirmRequest request =
                new YagaRefreshHideConfirmRequest("token", "HIDE");
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<YagaRefreshJobStatus>> calls = List.of(
                    () -> service.confirm(runId, jobId, request).jobStatus(),
                    () -> service.confirm(runId, jobId, request).jobStatus()
            );
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            assertThat(results.get(0).get())
                    .isEqualTo(YagaRefreshJobStatus.COMPLETED);
            assertThat(results.get(1).get())
                    .isEqualTo(YagaRefreshJobStatus.COMPLETED);
        }
        verify(manager, times(1)).confirmForRefresh(any(), any());
    }

    @Test
    void persistentAndSafeResponseTypesContainNoConfirmationToken() {
        assertThat(List.of(YagaRefreshJob.class.getDeclaredFields()))
                .extracting("name")
                .noneMatch(name -> name.toString().toLowerCase()
                        .contains("token"));
        assertThat(List.of(
                YagaRefreshHideResultResponse.class.getRecordComponents()
        )).extracting("name")
                .noneMatch(name -> name.toString().toLowerCase()
                        .contains("token"));
    }

    private void assertBlockedPreparation() {
        assertThatThrownBy(() -> service.prepare(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
        verify(manager, never()).prepare(any());
    }

    private void markAwaiting() {
        job.setStatus(YagaRefreshJobStatus.HIDING_OLD);
        job.setHidePreparationId(preparationId);
        job.setHideStatus(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
        job.setHidePreparedAt(clock.instant());
    }

    private void markUnknown() {
        job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
        job.setHidePreparationId(preparationId);
        job.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
        job.setHideConfirmStartedAt(clock.instant());
        run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
    }

    private MarketplaceListing listing(
            Long id,
            Product owner,
            String externalId,
            String slug,
            boolean current
    ) {
        MarketplaceListing listing = new MarketplaceListing(
                owner,
                Marketplace.YAGA,
                externalId,
                "https://www.yaga.ee/nik-ar/toode/" + slug
        );
        listing.setId(id);
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(slug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(current);
        listing.setExternalCreatedAt(clock.instant());
        listing.setCreatedAt(clock.instant());
        return listing;
    }

    private YagaHidePreparationResponse preparation() {
        return new YagaHidePreparationResponse(
                preparationId, 11L, 12L, 10L,
                "11", "old-slug", "12", "new-slug",
                oldListing.getExternalUrl(), 1, 1, 1,
                "hide", "hide", "button", "button", true,
                "one-time-token", clock.instant().plusSeconds(600),
                YagaHidingStatus.AWAITING_CONFIRMATION.name()
        );
    }

    private YagaHideReadinessResponse readiness() {
        return new YagaHideReadinessResponse(
                preparationId,
                YagaHidingStatus.AWAITING_CONFIRMATION,
                oldListing.getExternalUrl(),
                true, 1, 1, 1,
                "hide", "button", "button", true,
                clock.instant()
        );
    }

    private YagaHidePreparationStatusResponse status(YagaHidingStatus status) {
        return new YagaHidePreparationStatusResponse(
                preparationId, 11L, 12L, status, clock.instant(),
                clock.instant().plusSeconds(600), oldListing.getExternalUrl(),
                oldListing.getExternalUrl(), null
        );
    }

    private YagaHideConfirmResponse confirm(
            YagaHidingStatus status,
            boolean hidden
    ) {
        return new YagaHideConfirmResponse(
                preparationId, 11L, 12L, hidden,
                oldListing.getExternalUrl(), clock.instant(), status
        );
    }

    private YagaImportedProductData data(
            Long id,
            String slug,
            String status
    ) {
        return new YagaImportedProductData(
                id,
                "nik-ar", slug, "title", "description",
                BigDecimal.ONE, "EUR", status, null,
                List.of(), List.of(), clock.instant(), null, null, null
        );
    }
}
