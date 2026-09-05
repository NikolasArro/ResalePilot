package ee.nikolas.resalepilot.workflow.yaga.publishing;

import ee.nikolas.resalepilot.integration.drive.model.DownloadedDriveFile;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.automation.YagaBrowserAutomation;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedImageFile;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPrepareFormResponse;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPreparationAlreadyRunningException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDataInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDriveDownloadException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YagaPublishingServiceTest {

    @Mock
    private MarketplaceListingRepository listingRepository;

    @Mock
    private GoogleDriveService googleDriveService;

    @Mock
    private YagaBrowserAutomation browserAutomation;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void returnsFilledNotPublishedResponseAndPassesImagesInDisplayOrder()
            throws Exception {

        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Raamatud", "Ilukirjandus");
        addLinkedListingImage(listing, "drive-2", "second.jpg", 7, false);
        addLinkedListingImage(listing, "drive-1", "first.jpg", 5, true);

        Path firstPath = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );
        Path secondPath = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", firstPath));
        when(googleDriveService.downloadToTemporaryFile("drive-2"))
                .thenReturn(downloaded("drive-2", secondPath));
        when(browserAutomation.prepareForm(any(), any()))
                .thenReturn(formResult(
                        List.of("Raamatud", "Ilukirjandus"),
                        2
                ));

        YagaPrepareFormResponse response =
                service.prepareForm(10L);

        assertThat(response.status())
                .isEqualTo(YagaPublishingService.FILLED_NOT_PUBLISHED);
        assertThat(response.listingId()).isEqualTo(10L);
        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.imageCount()).isEqualTo(2);
        assertThat(response.descriptionFilled()).isTrue();
        assertThat(response.categoryPath())
                .containsExactly("Raamatud", "Ilukirjandus");
        assertThat(response.condition())
                .isEqualTo(ProductCondition.GOOD);
        assertThat(response.price())
                .isEqualByComparingTo("12.50");

        ArgumentCaptor<List<YagaPreparedImageFile>> filesCaptor =
                ArgumentCaptor.captor();
        verify(browserAutomation)
                .prepareForm(any(), filesCaptor.capture());

        assertThat(filesCaptor.getValue())
                .extracting(YagaPreparedImageFile::driveFileId)
                .containsExactly("drive-1", "drive-2");
        assertThat(filesCaptor.getValue())
                .extracting(YagaPreparedImageFile::displayOrder)
                .containsExactly(5, 7);
        assertThat(Files.notExists(firstPath)).isTrue();
        assertThat(Files.notExists(secondPath)).isTrue();
    }

    @Test
    void keepsCategoryPathOrderInSnapshot() throws Exception {
        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Root", "Child", "Leaf");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);
        Path path = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", path));
        when(browserAutomation.prepareForm(any(), any()))
                .thenReturn(formResult(
                        List.of("Root", "Child", "Leaf"),
                        1
                ));

        service.prepareForm(10L);

        ArgumentCaptor<ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData>
                draftCaptor = ArgumentCaptor.captor();
        verify(browserAutomation)
                .prepareForm(draftCaptor.capture(), any());

        assertThat(draftCaptor.getValue().categoryPath())
                .containsExactly("Root", "Child", "Leaf");
    }

    @Test
    void rejectsInvalidSnapshotBeforeDownloadingOrOpeningBrowser() {
        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        listing.getProduct().setDescription(" ");
        addCategories(listing, "Raamatud");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);
        mockListing(listing);

        assertThatThrownBy(() -> service.prepareForm(10L))
                .isInstanceOf(YagaPublishingDataInvalidException.class)
                .hasMessageContaining("description");

        verifyNoInteractions(googleDriveService);
        verifyNoInteractions(browserAutomation);
    }

    @Test
    void doesNotOpenBrowserAndCleansTempFilesWhenDriveDownloadFails()
            throws Exception {

        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Raamatud");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);
        addLinkedListingImage(listing, "drive-2", "second.jpg", 1, false);

        Path firstPath = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", firstPath));
        when(googleDriveService.downloadToTemporaryFile("drive-2"))
                .thenThrow(new GoogleDriveAccessException("drive failed"));

        assertThatThrownBy(() -> service.prepareForm(10L))
                .isInstanceOf(YagaPublishingDriveDownloadException.class);

        assertThat(Files.notExists(firstPath)).isTrue();
        verifyNoInteractions(browserAutomation);
    }

    @Test
    void cleansTempFilesWhenAutomationFails() throws Exception {
        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Raamatud");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);

        Path path = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", path));
        when(browserAutomation.prepareForm(any(), any()))
                .thenThrow(new IllegalStateException("browser failed"));

        assertThatThrownBy(() -> service.prepareForm(10L))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Failed to prepare Yaga listing form")
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(Files.notExists(path)).isTrue();
    }

    @Test
    void rejectsConcurrentPreparation() throws Exception {
        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Raamatud");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);

        Path path = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );
        CountDownLatch automationEntered = new CountDownLatch(1);
        CountDownLatch releaseAutomation = new CountDownLatch(1);

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", path));
        when(browserAutomation.prepareForm(any(), any()))
                .thenAnswer(invocation -> {
                    automationEntered.countDown();
                    releaseAutomation.await();
                    return formResult(List.of("Raamatud"), 1);
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<YagaPrepareFormResponse> running =
                    executor.submit(() -> service.prepareForm(10L));
            automationEntered.await();

            assertThatThrownBy(() -> service.prepareForm(10L))
                    .isInstanceOf(
                            YagaPreparationAlreadyRunningException.class
                    );

            releaseAutomation.countDown();
            assertThat(running.get().status())
                    .isEqualTo(
                            YagaPublishingService.FILLED_NOT_PUBLISHED
                    );
        } finally {
            releaseAutomation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsUnconfirmedFormResult() throws Exception {
        YagaPublishingService service = service();
        MarketplaceListing listing = listing();
        addCategories(listing, "Raamatud");
        addLinkedListingImage(listing, "drive-1", "first.jpg", 0, true);
        Path path = Files.createTempFile(
                "resalepilot-publishing-test-",
                ".jpg"
        );

        mockListing(listing);
        when(googleDriveService.downloadToTemporaryFile("drive-1"))
                .thenReturn(downloaded("drive-1", path));
        when(browserAutomation.prepareForm(any(), any()))
                .thenReturn(new YagaFormFillResult(
                        1,
                        false,
                        List.of("Raamatud"),
                        "Hea",
                        new BigDecimal("12.50"),
                        Path.of("screenshot.png")
                ));

        assertThatThrownBy(() -> service.prepareForm(10L))
                .isInstanceOf(YagaPublishingDataInvalidException.class)
                .hasMessageContaining("description");
    }

    @Test
    void browserAutomationAbstractionHasNoDangerousActions() {
        assertThat(Arrays.stream(YagaBrowserAutomation.class.getMethods())
                .map(Method::getName)
                .filter(name ->
                        name.equalsIgnoreCase("publish") ||
                                name.equalsIgnoreCase("hide") ||
                                name.equalsIgnoreCase("delete")
                ))
                .isEmpty();
    }

    private YagaPublishingService service() {
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        ))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        return new YagaPublishingService(
                listingRepository,
                googleDriveService,
                browserAutomation,
                transactionManager
        );
    }

    private void mockListing(MarketplaceListing listing) {
        when(listingRepository.findByIdWithCategories(10L))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findByIdWithImagesAndProductImages(10L))
                .thenReturn(Optional.of(listing));
    }

    private MarketplaceListing listing() {
        Product product = new Product("BOOK-001", "Kalevipoeg");
        product.setId(1L);
        product.setDescription("Imported description");
        product.setAskingPrice(new BigDecimal("12.50"));
        product.setCondition(ProductCondition.GOOD);

        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        "external-1",
                        "https://www.yaga.ee/shop/toode/item"
        );
        listing.setId(10L);
        listing.setShopSlug("shop");

        return listing;
    }

    private void addCategories(
            MarketplaceListing listing,
            String... path
    ) {
        for (int index = 0; index < path.length; index++) {
            listing.addCategory(
                    new MarketplaceListingCategory(
                            index,
                            (long) index + 1,
                            index == 0 ? null : (long) index,
                            path[index]
                    )
            );
        }
    }

    private void addLinkedListingImage(
            MarketplaceListing listing,
            String driveFileId,
            String fileName,
            int displayOrder,
            boolean primary
    ) {
        ProductImage productImage =
                new ProductImage(
                        listing.getProduct(),
                        driveFileId
                );
        productImage.setId((long) displayOrder + 100);
        productImage.setFileName(fileName);
        productImage.setDisplayOrder(displayOrder);
        productImage.setPrimaryImage(primary);

        MarketplaceListingImage listingImage =
                new MarketplaceListingImage(
                        "external-" + displayOrder,
                        "https://images.yaga.ee/" +
                                displayOrder +
                                ".jpg",
                        fileName,
                        displayOrder
                );
        listingImage.setProductImage(productImage);
        listing.addImage(listingImage);
    }

    private DownloadedDriveFile downloaded(
            String driveFileId,
            Path path
    ) {
        return new DownloadedDriveFile(
                driveFileId,
                driveFileId + ".jpg",
                "image/jpeg",
                path
        );
    }

    private YagaFormFillResult formResult(
            List<String> categoryPath,
            int imageCount
    ) {
        return new YagaFormFillResult(
                imageCount,
                true,
                categoryPath,
                "Hea",
                new BigDecimal("12.50"),
                Path.of("screenshot.png")
        );
    }
}
