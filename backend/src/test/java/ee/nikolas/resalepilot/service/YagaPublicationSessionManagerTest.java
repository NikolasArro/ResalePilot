package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.dto.*;
import ee.nikolas.resalepilot.entity.*;
import ee.nikolas.resalepilot.exception.*;
import ee.nikolas.resalepilot.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.repository.ProductImageRepository;
import ee.nikolas.resalepilot.yaga.YagaImportedProductData;
import ee.nikolas.resalepilot.yaga.YagaPageDataClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YagaPublicationSessionManagerTest {

    @Mock
    private YagaPublishingService publishingService;

    @Mock
    private YagaBrowserAutomation browserAutomation;

    @Mock
    private MarketplaceListingRepository listingRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private YagaPageDataClient pageDataClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private YagaPublicationSessionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void tokenHasEntropyAndIsStoredAsHash() {
        YagaConfirmationTokenService tokenService =
                new YagaConfirmationTokenService();

        String first = tokenService.generateToken();
        String second = tokenService.generateToken();
        byte[] hash = tokenService.hashToken(first);

        assertThat(first).hasSizeGreaterThanOrEqualTo(43);
        assertThat(second).isNotEqualTo(first);
        assertThat(hash).hasSize(32);
        assertThat(tokenService.matches(first, hash)).isTrue();
        assertThat(tokenService.matches(second, hash)).isFalse();
    }

    @Test
    void prepareReturnsTokenOnceAndStatusDoesNotExposeIt()
            throws Exception {

        manager = manager(false, Duration.ofMinutes(10));
        mockSuccessfulPrepare();

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublicationPreparationStatusResponse status =
                manager.status(response.preparationId());

        assertThat(response.confirmationToken()).isNotBlank();
        assertThat(status.status())
                .isEqualTo(
                        YagaPublicationStatus.AWAITING_CONFIRMATION
                );
        assertThat(status.screenshotPath())
                .isEqualTo("screenshot.png");
    }

    @Test
    void confirmFlagFalseRejectsConfirm() throws Exception {
        manager = manager(false, Duration.ofMinutes(10));
        mockSuccessfulPrepare();

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        ))
                .isInstanceOf(
                        YagaPublicationConfirmDisabledException.class
                );

        verify(browserAutomation, never())
                .publishPreparedSession(any());
    }

    @Test
    void readinessWorksWhenConfirmFlagIsFalse() throws Exception {
        manager = manager(false, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        when(browserAutomation.inspectPublishControl(any()))
                .thenReturn(new YagaPublishControlInspection(
                        "https://www.yaga.ee/muuk/lisa-toode",
                        true,
                        1,
                        1,
                        1,
                        "Valmis",
                        "button",
                        "submit",
                        true,
                        Instant.now()
                ));

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublishReadinessResponse readiness =
                manager.publishReadiness(response.preparationId());

        assertThat(readiness.readyForConfirmation()).isTrue();
        assertThat(readiness.sessionStatus())
                .isEqualTo(
                        YagaPublicationStatus.AWAITING_CONFIRMATION
                );
        verify(browserAutomation).inspectPublishControl(any());
        verify(browserAutomation, never())
                .publishPreparedSession(any());
    }

    @Test
    void wrongTokenAndWrongPhraseAreForbidden()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        "wrong",
                        "PUBLISH"
                )
        ))
                .isInstanceOf(YagaPublicationForbiddenException.class);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "publish"
                )
        ))
                .isInstanceOf(YagaPublicationForbiddenException.class);

        verify(browserAutomation, never())
                .publishPreparedSession(any());
    }

    @Test
    void expiredTokenReturnsGoneAndClosesSession()
            throws Exception {

        manager = manager(true, Duration.ofMillis(50));
        mockSuccessfulPrepare();

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);

        Thread.sleep(200);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        ))
                .isInstanceOf(YagaPublicationExpiredException.class);

        verify(browserAutomation).closeSession(any());
    }

    @Test
    void concurrentSecondPrepareIsRejected()
            throws Exception {

        manager = manager(false, Duration.ofMinutes(10));
        mockSnapshotAndDownloads();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(browserAutomation.prepareSession(any(), any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await();
                    return browserSession(invocation.getArgument(0));
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<YagaPublicationPreparationResponse> running =
                    executor.submit(() -> manager.prepare(10L));
            entered.await();

            assertThatThrownBy(() -> manager.prepare(10L))
                    .isInstanceOf(
                            YagaPreparationAlreadyRunningException.class
                    );

            release.countDown();
            assertThat(running.get().status())
                    .isEqualTo(
                            YagaPublicationStatus.AWAITING_CONFIRMATION
                    );
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelClosesSessionAndNeverPublishes()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublicationPreparationStatusResponse cancelled =
                manager.cancel(response.preparationId());

        assertThat(cancelled.status())
                .isEqualTo(YagaPublicationStatus.CANCELLED);
        verify(browserAutomation).closeSession(any());
        verify(browserAutomation, never())
                .publishPreparedSession(any());
    }

    @Test
    void verifyFailureFailsBeforePublishClick()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        when(browserAutomation.verifyPreparedForm(any()))
                .thenThrow(new YagaPublishingFormException(
                        "verify failed"
                ));

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        ))
                .isInstanceOf(YagaPublishingFormException.class);

        assertThat(manager.status(response.preparationId()).status())
                .isEqualTo(YagaPublicationStatus.FAILED);
        verify(browserAutomation, never())
                .publishPreparedSession(any());
    }

    @Test
    void afterClickUnknownResultCannotBeConfirmedAgain()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        when(browserAutomation.verifyPreparedForm(any()))
                .thenReturn(formResult());
        when(browserAutomation.publishPreparedSession(any()))
                .thenReturn(new YagaPublishResult(
                        true,
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                        "https://www.yaga.ee/muuk/lisa-toode",
                        null,
                        null,
                        Instant.now()
                ));

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublicationConfirmResponse confirm =
                manager.confirm(
                        response.preparationId(),
                        new YagaPublicationConfirmRequest(
                                response.confirmationToken(),
                                "PUBLISH"
                        )
                );

        assertThat(confirm.status())
                .isEqualTo(
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
                );
        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        ))
                .isInstanceOf(
                        YagaPublicationInvalidStateException.class
                );
        verify(browserAutomation, times(1))
                .publishPreparedSession(any());
    }

    @Test
    void confirmedSuccessParsesUrlAndSyncsDatabase()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        mockTransaction();
        when(browserAutomation.verifyPreparedForm(any()))
                .thenReturn(formResult());
        when(browserAutomation.publishPreparedSession(any()))
                .thenReturn(new YagaPublishResult(
                        true,
                        YagaPublicationStatus.PUBLISHED,
                        "https://www.yaga.ee/shop/toode/new-book",
                        "shop",
                        "new-book",
                        Instant.now()
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/shop/toode/new-book"
        ))
                .thenReturn(importedData());

        Product product = product();
        MarketplaceListing oldListing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        "old",
                        "https://old"
                );
        when(listingRepository.findByIdWithImagesAndProductImages(10L))
                .thenReturn(Optional.of(oldListing));
        when(productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(productImage(product, "drive-1")));

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublicationConfirmResponse confirm =
                manager.confirm(
                        response.preparationId(),
                        new YagaPublicationConfirmRequest(
                                response.confirmationToken(),
                                "PUBLISH"
                        )
                );

        assertThat(confirm.status())
                .isEqualTo(YagaPublicationStatus.PUBLISHED);
        assertThat(confirm.newShopSlug()).isEqualTo("shop");
        assertThat(confirm.newProductSlug()).isEqualTo("new-book");

        ArgumentCaptor<MarketplaceListing> listingCaptor =
                ArgumentCaptor.captor();
        verify(listingRepository)
                .saveAndFlush(listingCaptor.capture());
        assertThat(listingCaptor.getValue().getProduct())
                .isSameAs(product);
        assertThat(listingCaptor.getValue().getImages())
                .hasSize(1);
        assertThat(listingCaptor.getValue().getImages().getFirst()
                .getProductImage().getDriveFileId())
                .isEqualTo("drive-1");
    }

    @Test
    void dbSyncFailureDoesNotRetryPublish()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        mockTransaction();
        when(browserAutomation.verifyPreparedForm(any()))
                .thenReturn(formResult());
        when(browserAutomation.publishPreparedSession(any()))
                .thenReturn(new YagaPublishResult(
                        true,
                        YagaPublicationStatus.PUBLISHED,
                        "https://www.yaga.ee/shop/toode/new-book",
                        "shop",
                        "new-book",
                        Instant.now()
                ));
        when(pageDataClient.getProduct(any()))
                .thenReturn(importedData());
        when(listingRepository.findByIdWithImagesAndProductImages(10L))
                .thenThrow(new IllegalStateException("db failed"));

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        YagaPublicationConfirmResponse confirm =
                manager.confirm(
                        response.preparationId(),
                        new YagaPublicationConfirmRequest(
                                response.confirmationToken(),
                                "PUBLISH"
                        )
                );

        assertThat(confirm.status())
                .isEqualTo(
                        YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED
                );
        assertThat(confirm.published()).isTrue();
        verify(browserAutomation, times(1))
                .publishPreparedSession(any());
    }

    @Test
    void sessionOperationsUseOneExecutorThread()
            throws Exception {

        manager = manager(true, Duration.ofMinutes(10));
        mockSuccessfulPrepare();
        AtomicReference<String> prepareThread =
                new AtomicReference<>();
        AtomicReference<String> verifyThread =
                new AtomicReference<>();
        AtomicReference<String> publishThread =
                new AtomicReference<>();

        when(browserAutomation.prepareSession(any(), any()))
                .thenAnswer(invocation -> {
                    prepareThread.set(
                            Thread.currentThread().getName()
                    );
                    return browserSession(invocation.getArgument(0));
                });
        when(browserAutomation.verifyPreparedForm(any()))
                .thenAnswer(invocation -> {
                    verifyThread.set(
                            Thread.currentThread().getName()
                    );
                    return formResult();
                });
        when(browserAutomation.publishPreparedSession(any()))
                .thenAnswer(invocation -> {
                    publishThread.set(
                            Thread.currentThread().getName()
                    );
                    return unknownResult();
                });

        YagaPublicationPreparationResponse response =
                manager.prepare(10L);
        manager.confirm(
                response.preparationId(),
                new YagaPublicationConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        );

        assertThat(prepareThread.get()).isEqualTo(verifyThread.get());
        assertThat(verifyThread.get()).isEqualTo(publishThread.get());
    }

    private YagaPublicationSessionManager manager(
            boolean confirmEnabled,
            Duration ttl
    ) {
        YagaPublishingProperties properties =
                new YagaPublishingProperties();
        properties.setConfirmEnabled(confirmEnabled);
        properties.setConfirmationTtl(ttl);

        return new YagaPublicationSessionManager(
                publishingService,
                browserAutomation,
                new YagaConfirmationTokenService(),
                properties,
                listingRepository,
                productImageRepository,
                pageDataClient,
                transactionManager
        );
    }

    private void mockSuccessfulPrepare() throws Exception {
        mockSnapshotAndDownloads();
        when(browserAutomation.prepareSession(any(), any()))
                .thenAnswer(invocation ->
                        browserSession(invocation.getArgument(0))
                );
    }

    private void mockSnapshotAndDownloads() throws Exception {
        Path image = Files.createTempFile(
                "resalepilot-publication-test-",
                ".jpg"
        );
        YagaListingDraftData draft = draft();

        when(publishingService.loadDraft(10L))
                .thenReturn(draft);
        when(publishingService.downloadImages(draft))
                .thenReturn(List.of(new DownloadedDriveFile(
                        "drive-1",
                        "image.jpg",
                        "image/jpeg",
                        image
                )));
        when(publishingService.toPreparedImageFiles(any(), any()))
                .thenReturn(List.of(new YagaPreparedImageFile(
                        "drive-1",
                        "image.jpg",
                        0,
                        true,
                        image
                )));
    }

    private void mockTransaction() {
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        ))
                .thenReturn(new SimpleTransactionStatus());
    }

    private YagaListingDraftData draft() {
        return new YagaListingDraftData(
                10L,
                1L,
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                ProductCondition.GOOD,
                List.of("Raamatud"),
                List.of(new YagaListingDraftData.Image(
                        "drive-1",
                        "image.jpg",
                        0,
                        true
                ))
        );
    }

    private YagaPreparedBrowserSession browserSession(
            YagaListingDraftData draft
    ) {
        return new FakeSession(draft);
    }

    private YagaFormFillResult formResult() {
        return new YagaFormFillResult(
                1,
                true,
                List.of("Raamatud"),
                "Hea",
                new BigDecimal("17.00"),
                Path.of("screenshot.png")
        );
    }

    private YagaPublishResult unknownResult() {
        return new YagaPublishResult(
                true,
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                "https://www.yaga.ee/muuk/lisa-toode",
                null,
                null,
                Instant.now()
        );
    }

    private Product product() {
        Product product = new Product("BOOK-001", "Kalevipoeg");
        product.setId(1L);
        return product;
    }

    private ProductImage productImage(
            Product product,
            String driveFileId
    ) {
        ProductImage image =
                new ProductImage(product, driveFileId);
        image.setDisplayOrder(0);
        image.setPrimaryImage(true);
        return image;
    }

    private YagaImportedProductData importedData() {
        return new YagaImportedProductData(
                200L,
                "shop",
                "new-book",
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                "published",
                new YagaImportedProductData.Condition(3L, "Hea"),
                List.of(new YagaImportedProductData.Category(
                        1L,
                        null,
                        "Raamatud",
                        List.of()
                )),
                List.of(new YagaImportedProductData.Image(
                        "img-1",
                        "https://images.yaga.ee/img-1.jpg",
                        "image.jpg"
                )),
                Instant.now(),
                Instant.now(),
                null,
                null
        );
    }

    private record FakeSession(
            UUID sessionId,
            YagaListingDraftData draft,
            YagaFormFillResult preparedForm
    ) implements YagaPreparedBrowserSession {

        private FakeSession(YagaListingDraftData draft) {
            this(UUID.randomUUID(), draft, new YagaFormFillResult(
                    1,
                    true,
                    List.of("Raamatud"),
                    "Hea",
                    new BigDecimal("17.00"),
                    Path.of("screenshot.png")
            ));
        }
    }
}
