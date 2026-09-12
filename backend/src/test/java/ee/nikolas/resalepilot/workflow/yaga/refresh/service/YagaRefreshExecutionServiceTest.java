package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublicationSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.YagaPublicationReconciliationService;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

class YagaRefreshExecutionServiceTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID preparationId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-08T12:00:00Z"),
            ZoneOffset.UTC
    );

    private YagaRefreshRunRepository runRepository;
    private MarketplaceListingRepository listingRepository;
    private ProductImageRepository productImageRepository;
    private YagaPublicationSessionManager sessionManager;
    private YagaPublicationReconciliationService reconciliationService;
    private PlatformTransactionManager transactionManager;
    private YagaRefreshExecutionService service;
    private YagaRefreshRun run;
    private YagaRefreshJob job;
    private MarketplaceListing oldListing;
    private Product product;

    @BeforeEach
    void setUp() {
        runRepository = mock(YagaRefreshRunRepository.class);
        listingRepository = mock(MarketplaceListingRepository.class);
        productImageRepository = mock(ProductImageRepository.class);
        sessionManager = mock(YagaPublicationSessionManager.class);
        reconciliationService = mock(YagaPublicationReconciliationService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        )).thenReturn(new SimpleTransactionStatus());

        @SuppressWarnings("unchecked")
        ObjectProvider<YagaPublicationSessionManager> sessionProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaPublicationReconciliationService> reconciliationProvider =
                mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessionManager);
        when(reconciliationProvider.getIfAvailable())
                .thenReturn(reconciliationService);

        service = new YagaRefreshExecutionService(
                runRepository,
                listingRepository,
                productImageRepository,
                sessionProvider,
                reconciliationProvider,
                transactionManager,
                clock
        );

        product = product(33L, "BOOK-033");
        oldListing = listing(33L, product, "old-external", "65anmhkt7q8");
        ProductImage productImage = productImage(product);
        oldListing.addImage(listingImage(productImage));

        run = new YagaRefreshRun(
                YagaRefreshTriggerType.MANUAL,
                YagaRefreshRunMode.MANUAL,
                1,
                "manual-refresh",
                clock.instant()
        );
        run.setId(runId);
        run.setStatus(YagaRefreshRunStatus.AWAITING_CONFIRMATION);
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
                1,
                1,
                0,
                clock.instant()
        );
        job.setId(jobId);
        run.addJob(job);

        when(runRepository.findForUpdateWithJobsById(runId))
                .thenReturn(Optional.of(run));
        when(listingRepository.findByIdWithImagesAndProductImages(33L))
                .thenReturn(Optional.of(oldListing));
        when(productImageRepository.findAllByProductIdOrderByDisplayOrderAsc(33L))
                .thenReturn(List.of(productImage));
        when(listingRepository.findAllByProductIdAndMarketplaceAndStatus(
                33L,
                Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED
        )).thenReturn(List.of(oldListing));
    }

    @Test
    void preparePublicationDelegatesToExistingPublishingWorkflowWithoutConfirm() {
        when(sessionManager.prepare(33L))
                .thenReturn(preparationResponse());
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var response = service.preparePublication(runId, jobId);

        assertThat(response.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(response.confirmationToken()).isEqualTo("token-once");
        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.PUBLISHING);
        assertThat(job.getPublicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(job.getPublicationStatus())
                .isEqualTo(YagaPublicationStatus.AWAITING_CONFIRMATION.name());
        verify(sessionManager, never())
                .confirm(any(), any());
    }

    @Test
    void activeExistingPreparationIsReturnedWithoutSecondPrepare() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        job.setPublicationStatus(
                YagaPublicationStatus.AWAITING_CONFIRMATION.name()
        );
        when(sessionManager.status(preparationId))
                .thenReturn(statusResponse(
                        preparationId,
                        YagaPublicationStatus.AWAITING_CONFIRMATION
                ));
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var response = service.preparePublication(runId, jobId);

        assertThat(response.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(response.confirmationToken()).isNull();
        verify(sessionManager, never()).prepare(any());
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void expiredExistingPreparationCreatesNewPreparation() {
        UUID expiredPreparationId = UUID.randomUUID();
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(expiredPreparationId);
        job.setPublicationStatus(YagaPublicationStatus.EXPIRED.name());
        when(sessionManager.status(expiredPreparationId))
                .thenReturn(statusResponse(
                        expiredPreparationId,
                        YagaPublicationStatus.EXPIRED
                ));
        when(sessionManager.prepare(33L))
                .thenReturn(preparationResponse());
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var response = service.preparePublication(runId, jobId);

        assertThat(response.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(response.publicationPreparationId())
                .isNotEqualTo(expiredPreparationId);
        assertThat(response.confirmationToken()).isEqualTo("token-once");
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.PUBLISHING);
        assertThat(job.getPublicationPreparationId())
                .isEqualTo(preparationId);
        verify(sessionManager).cancel(expiredPreparationId);
        verify(sessionManager).prepare(33L);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void cancelledExistingPreparationCreatesNewPreparation() {
        UUID cancelledPreparationId = UUID.randomUUID();
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(cancelledPreparationId);
        job.setPublicationStatus(YagaPublicationStatus.CANCELLED.name());
        when(sessionManager.status(cancelledPreparationId))
                .thenReturn(statusResponse(
                        cancelledPreparationId,
                        YagaPublicationStatus.CANCELLED
                ));
        when(sessionManager.prepare(33L))
                .thenReturn(preparationResponse());
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var response = service.preparePublication(runId, jobId);

        assertThat(response.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(response.confirmationToken()).isEqualTo("token-once");
        verify(sessionManager).cancel(cancelledPreparationId);
        verify(sessionManager).prepare(33L);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void resultUnknownPreparationCannotBePreparedAgain() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        when(sessionManager.status(preparationId))
                .thenReturn(statusResponse(
                        preparationId,
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
                ));

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("reconciliation");

        assertThat(job.getStatus())
                .isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        verify(sessionManager, never()).prepare(any());
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void missingSessionWithoutConfirmStartedCanBePreparedAgain() {
        UUID missingPreparationId = UUID.randomUUID();
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(missingPreparationId);
        when(sessionManager.status(missingPreparationId))
                .thenThrow(new YagaPublicationPreparationNotFoundException(
                        missingPreparationId
                ));
        when(sessionManager.prepare(33L))
                .thenReturn(preparationResponse());
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var response = service.preparePublication(runId, jobId);

        assertThat(response.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(response.confirmationToken()).isEqualTo("token-once");
        assertThat(job.getPublicationPreparationId())
                .isEqualTo(preparationId);
        verify(sessionManager).prepare(33L);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void missingSessionAfterConfirmStartedCannotBePreparedAgain() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        job.setPublicationConfirmStartedAt(clock.instant());
        when(sessionManager.status(preparationId))
                .thenThrow(new YagaPublicationPreparationNotFoundException(
                        preparationId
                ));

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("reconciliation");

        assertThat(job.getStatus())
                .isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        verify(sessionManager, never()).prepare(any());
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void failedNewPreparationDoesNotStoreFalsePreparationId() {
        UUID expiredPreparationId = UUID.randomUUID();
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(expiredPreparationId);
        job.setPublicationStatus(YagaPublicationStatus.EXPIRED.name());
        when(sessionManager.status(expiredPreparationId))
                .thenReturn(statusResponse(
                        expiredPreparationId,
                        YagaPublicationStatus.EXPIRED
                ));
        when(sessionManager.prepare(33L))
                .thenThrow(new IllegalStateException("prepare failed"));

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prepare failed");

        assertThat(job.getPublicationPreparationId())
                .isEqualTo(expiredPreparationId);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.PUBLISHING);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void failedInitialPreparationCanBeRetried() {
        when(sessionManager.prepare(33L))
                .thenThrow(new IllegalStateException("prepare failed"))
                .thenReturn(preparationResponse());
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prepare failed");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.SELECTED);
        assertThat(job.getPublicationPreparationId()).isNull();
        assertThat(job.getLastErrorCode())
                .isEqualTo("PUBLICATION_PREPARATION_FAILED");

        var retried = service.preparePublication(runId, jobId);

        assertThat(retried.publicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(job.getStatus())
                .isEqualTo(YagaRefreshJobStatus.PUBLISHING);
        assertThat(job.getPublicationPreparationId())
                .isEqualTo(preparationId);
        assertThat(job.getLastErrorCode()).isNull();
        verify(sessionManager, times(2)).prepare(33L);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void concurrentPrepareCreatesAtMostOneNewSession() throws Exception {
        when(sessionManager.prepare(33L))
                .thenAnswer(invocation -> preparationResponse());
        when(sessionManager.status(preparationId))
                .thenReturn(statusResponse(
                        preparationId,
                        YagaPublicationStatus.AWAITING_CONFIRMATION
                ));
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        var executor = Executors.newFixedThreadPool(2);
        Callable<Object> task = () -> service.preparePublication(runId, jobId);
        var first = executor.submit(task);
        var second = executor.submit(task);

        Object firstResponse = first.get(5, TimeUnit.SECONDS);
        Object secondResponse = second.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(firstResponse).isNotNull();
        assertThat(secondResponse).isNotNull();
        verify(sessionManager, times(1)).prepare(33L);
        verify(sessionManager, never()).confirm(any(), any());
    }

    @Test
    void readinessUsesLivePublicationSessionAndDoesNotConfirm() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));

        YagaPublishReadinessResponse response =
                service.publicationReadiness(runId, jobId);

        assertThat(response.readyForConfirmation()).isTrue();
        verify(sessionManager, never())
                .confirm(any(), any());
    }

    @Test
    void snapshotMismatchFailsBeforePublishingWorkflow() {
        oldListing.setProductSlug("changed");

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("snapshot");

        verify(sessionManager, never()).prepare(any());
    }

    @Test
    void hiddenOldListingFailsBeforePublishingWorkflow() {
        oldListing.setHiddenAt(clock.instant());

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("no longer eligible");

        verify(sessionManager, never()).prepare(any());
    }

    @Test
    void incompleteImageLinksFailBeforePublishingWorkflow() {
        oldListing.getImages().getFirst().setProductImage(null);

        assertThatThrownBy(() -> service.preparePublication(runId, jobId))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("images");

        verify(sessionManager, never()).prepare(any());
    }

    @Test
    void prepareWithoutPublishingWorkflowFailsBeforeChangingJob() {
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaPublicationSessionManager> missingSessionProvider =
                mock(ObjectProvider.class);
        when(missingSessionProvider.getIfAvailable()).thenReturn(null);

        YagaRefreshExecutionService serviceWithoutPublishing =
                new YagaRefreshExecutionService(
                        runRepository,
                        listingRepository,
                        productImageRepository,
                        missingSessionProvider,
                        mock(ObjectProvider.class),
                        transactionManager,
                        clock
                );

        assertThatThrownBy(() ->
                serviceWithoutPublishing.preparePublication(runId, jobId)
        )
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("publishing workflow");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.SELECTED);
        assertThat(job.getPublicationPreparationId()).isNull();
        verify(sessionManager, never()).prepare(any());
    }

    @Test
    void confirmLinksNewListingAndLeavesOldListingUntouched() {
        MarketplaceListing newListing =
                listing(44L, product, "new-external", "new-slug");
        newListing.setCurrent(false);
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));
        when(sessionManager.confirm(
                preparationId,
                new YagaPublicationConfirmRequest("token", "PUBLISH")
        )).thenReturn(confirmResponse(YagaPublicationStatus.PUBLISHED));
        when(listingRepository.findByMarketplaceAndShopSlugAndProductSlug(
                Marketplace.YAGA,
                "nik-ar",
                "new-slug"
        )).thenReturn(Optional.of(newListing));

        YagaRefreshPublicationResultResponse response =
                service.confirmPublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationConfirmRequest(
                                "token",
                                "PUBLISH"
                        )
                );

        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        assertThat(response.newListingId()).isEqualTo(44L);
        assertThat(oldListing.getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(oldListing.isCurrent()).isTrue();
        assertThat(oldListing.getHiddenAt()).isNull();
        assertThat(newListing.isCurrent()).isFalse();
        assertThat(job.getPublicationConfirmStartedAt())
                .isEqualTo(clock.instant());
        verify(sessionManager).confirm(any(), any());
    }

    @Test
    void repeatedConfirmAfterNewListingConfirmedDoesNotClickAgain() {
        MarketplaceListing newListing =
                listing(44L, product, "new-external", "new-slug");
        job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        job.setNewListing(newListing);
        job.setNewExternalListingId("new-external");
        job.setNewShopSlug("nik-ar");
        job.setNewProductSlug("new-slug");
        job.setNewProductUrl("https://www.yaga.ee/nik-ar/toode/new-slug");

        YagaRefreshPublicationResultResponse response =
                service.confirmPublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationConfirmRequest(
                                "token",
                                "PUBLISH"
                        )
                );

        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        verify(sessionManager, never())
                .confirm(any(), any());
    }

    @Test
    void unknownResultMarksJobUnknownAndCannotPublishAgain() {
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationPreparationId(preparationId);
        when(sessionManager.publishReadiness(preparationId))
                .thenReturn(readiness(true));
        when(sessionManager.confirm(any(), any()))
                .thenReturn(confirmResponse(
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
                ));

        YagaRefreshPublicationResultResponse response =
                service.confirmPublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationConfirmRequest(
                                "token",
                                "PUBLISH"
                        )
                );

        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);

        assertThatThrownBy(() ->
                service.confirmPublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationConfirmRequest(
                                "token",
                                "PUBLISH"
                        )
                ))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
    }

    @Test
    void recoveryWithoutPlaywrightLinksNewListingIdempotently() {
        job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
        MarketplaceListing newListing =
                listing(44L, product, "new-external", "new-slug");
        when(reconciliationService.reconcile(
                33L,
                new YagaListingPublicationReconcileRequest(
                        "https://www.yaga.ee/nik-ar/toode/new-slug"
                )
        )).thenReturn(reconcileResponse());
        when(listingRepository.findById(44L))
                .thenReturn(Optional.of(newListing));

        YagaRefreshPublicationResultResponse response =
                service.reconcilePublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationReconcileRequest(
                                "https://www.yaga.ee/nik-ar/toode/new-slug"
                        )
                );
        YagaRefreshPublicationResultResponse repeated =
                service.reconcilePublication(
                        runId,
                        jobId,
                        new YagaRefreshPublicationReconcileRequest(
                                "https://www.yaga.ee/nik-ar/toode/new-slug"
                        )
                );

        assertThat(response.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        assertThat(repeated.jobStatus())
                .isEqualTo(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        verify(sessionManager, never()).confirm(any(), any());
    }

    private Product product(Long id, String sku) {
        Product product = new Product(sku, "Макс Фраи, \"Чужак\"");
        product.setId(id);
        product.setStatus(ProductStatus.DRAFT);
        product.setDescription("description");
        product.setAskingPrice(new BigDecimal("17.00"));
        product.setCondition(ProductCondition.GOOD);
        return product;
    }

    private MarketplaceListing listing(
            Long id,
            Product product,
            String externalListingId,
            String productSlug
    ) {
        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        externalListingId,
                        "https://www.yaga.ee/nik-ar/toode/" + productSlug
                );
        listing.setId(id);
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(true);
        listing.setExternalCreatedAt(
                Instant.parse("2026-01-01T00:00:00Z")
        );
        listing.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return listing;
    }

    private ProductImage productImage(Product product) {
        ProductImage image = new ProductImage(product, "drive-1");
        image.setDisplayOrder(0);
        image.setPrimaryImage(true);
        return image;
    }

    private MarketplaceListingImage listingImage(ProductImage productImage) {
        MarketplaceListingImage image =
                new MarketplaceListingImage(
                        "img-1",
                        "https://images.yaga.ee/img-1.jpg",
                        "img-1.jpg",
                        0
                );
        image.setProductImage(productImage);
        return image;
    }

    private YagaPublicationPreparationResponse preparationResponse() {
        return new YagaPublicationPreparationResponse(
                preparationId,
                33L,
                33L,
                1,
                List.of("Raamatud"),
                ProductCondition.GOOD,
                new BigDecimal("17.00"),
                "screenshot.png",
                "token-once",
                clock.instant().plusSeconds(600),
                YagaPublicationStatus.AWAITING_CONFIRMATION
        );
    }

    private YagaPublicationPreparationStatusResponse statusResponse(
            UUID id,
            YagaPublicationStatus status
    ) {
        return new YagaPublicationPreparationStatusResponse(
                id,
                33L,
                status,
                clock.instant(),
                clock.instant().plusSeconds(600),
                "screenshot.png",
                status == YagaPublicationStatus.PUBLISHED
                        ? "https://www.yaga.ee/nik-ar/toode/new-slug"
                        : null,
                null
        );
    }

    private YagaPublishReadinessResponse readiness(boolean ready) {
        return new YagaPublishReadinessResponse(
                preparationId,
                YagaPublicationStatus.AWAITING_CONFIRMATION,
                "https://www.yaga.ee/muuk/lisa-toode",
                ready,
                ready ? 1 : 0,
                ready ? 1 : 0,
                ready ? 1 : 0,
                ready ? "Valmis" : null,
                ready ? "button" : null,
                ready ? "button" : null,
                ready,
                clock.instant()
        );
    }

    private YagaPublicationConfirmResponse confirmResponse(
            YagaPublicationStatus status
    ) {
        return new YagaPublicationConfirmResponse(
                preparationId,
                33L,
                status == YagaPublicationStatus.PUBLISHED,
                "https://www.yaga.ee/nik-ar/toode/new-slug",
                "nik-ar",
                "new-slug",
                clock.instant(),
                status
        );
    }

    private YagaListingPublicationReconcileResponse reconcileResponse() {
        return new YagaListingPublicationReconcileResponse(
                33L,
                44L,
                33L,
                true,
                "new-external",
                "https://www.yaga.ee/nik-ar/toode/new-slug",
                "nik-ar",
                "new-slug",
                1,
                YagaPublicationStatus.PUBLISHED
        );
    }
}
