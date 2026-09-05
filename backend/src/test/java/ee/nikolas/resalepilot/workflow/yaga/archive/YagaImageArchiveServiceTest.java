package ee.nikolas.resalepilot.workflow.yaga.archive;

import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;
import ee.nikolas.resalepilot.integration.drive.storage.DriveArchiveStorage;

import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;
import ee.nikolas.resalepilot.integration.yaga.downloader.YagaImageDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YagaImageArchiveServiceTest {

    @Mock
    private MarketplaceListingRepository listingRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private YagaImageDownloader imageDownloader;

    @Mock
    private DriveArchiveStorage driveArchiveStorage;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void archivesMissingImagesAfterExistingPrimary()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        MarketplaceListingImage firstListingImage =
                listingImage("external-1", 0);
        MarketplaceListingImage secondListingImage =
                listingImage("external-2", 1);

        listing.addImage(firstListingImage);
        listing.addImage(secondListingImage);

        ProductImage existingPrimary =
                new ProductImage(product, "existing-drive");
        existingPrimary.setDisplayOrder(4);
        existingPrimary.setPrimaryImage(true);

        Path firstPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        Path secondPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );

        List<DownloadedYagaImage> downloadedImages = List.of(
                downloaded("external-1", firstPath),
                downloaded("external-2", secondPath)
        );

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findByIdWithImagesForUpdate(10L))
                .thenReturn(Optional.of(listing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenReturn(List.of(
                        archived("external-1", "drive-1"),
                        archived("external-2", "drive-2")
                ));
        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.of(existingPrimary));
        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.of(existingPrimary));
        when(productImageRepository.save(any(ProductImage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        YagaArchiveImagesResponse response =
                service.archiveImages(10L);

        assertThat(response.archivedImageCount()).isEqualTo(2);
        assertThat(response.alreadyLinkedImageCount()).isZero();
        assertThat(response.images())
                .extracting("externalImageId")
                .containsExactly("external-1", "external-2");
        assertThat(response.images())
                .extracting("driveFileId")
                .containsExactly("drive-1", "drive-2");
        assertThat(firstListingImage.getProductImage()
                .getDriveFileId()).isEqualTo("drive-1");
        assertThat(secondListingImage.getProductImage()
                .getDriveFileId()).isEqualTo("drive-2");
        assertThat(firstListingImage.getProductImage()
                .getDisplayOrder()).isEqualTo(5);
        assertThat(secondListingImage.getProductImage()
                .getDisplayOrder()).isEqualTo(6);
        assertThat(firstListingImage.getProductImage()
                .isPrimaryImage()).isFalse();
        assertThat(secondListingImage.getProductImage()
                .isPrimaryImage()).isFalse();
        assertThat(Files.notExists(firstPath)).isTrue();
        assertThat(Files.notExists(secondPath)).isTrue();

        verify(driveArchiveStorage)
                .deleteCreatedFiles(List.of());
    }

    @Test
    void makesFirstImportedImagePrimaryWhenProductHasNoPrimary()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        MarketplaceListingImage firstListingImage =
                listingImage("external-1", 0);
        MarketplaceListingImage secondListingImage =
                listingImage("external-2", 1);

        listing.addImage(firstListingImage);
        listing.addImage(secondListingImage);

        Path firstPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        Path secondPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        List<DownloadedYagaImage> downloadedImages = List.of(
                downloaded("external-1", firstPath),
                downloaded("external-2", secondPath)
        );

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findByIdWithImagesForUpdate(10L))
                .thenReturn(Optional.of(listing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenReturn(List.of(
                        archived("external-1", "drive-1"),
                        archived("external-2", "drive-2")
                ));
        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.empty());
        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.empty());
        when(productImageRepository.save(any(ProductImage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        YagaArchiveImagesResponse response =
                service.archiveImages(10L);

        assertThat(response.archivedImageCount()).isEqualTo(2);
        assertThat(firstListingImage.getProductImage()
                .isPrimaryImage()).isTrue();
        assertThat(secondListingImage.getProductImage()
                .isPrimaryImage()).isFalse();
        assertThat(firstListingImage.getProductImage()
                .getDisplayOrder()).isZero();
        assertThat(secondListingImage.getProductImage()
                .getDisplayOrder()).isEqualTo(1);
        assertThat(response.images())
                .extracting("primary")
                .containsExactly(true, false);
    }

    @Test
    void archivesOnlyMissingImagesWhenListingIsPartiallyArchived()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        MarketplaceListingImage linkedListingImage =
                listingImage("external-1", 0);
        ProductImage linkedProductImage =
                new ProductImage(product, "existing-drive");
        linkedProductImage.setId(100L);
        linkedProductImage.setFileName("existing.jpg");
        linkedProductImage.setDisplayOrder(0);
        linkedProductImage.setPrimaryImage(true);
        linkedListingImage.setProductImage(linkedProductImage);

        MarketplaceListingImage missingListingImage =
                listingImage("external-2", 1);
        listing.addImage(linkedListingImage);
        listing.addImage(missingListingImage);

        Path path = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        List<DownloadedYagaImage> downloadedImages =
                List.of(downloaded("external-2", path));

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findByIdWithImagesForUpdate(10L))
                .thenReturn(Optional.of(listing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenReturn(List.of(
                        archived("external-2", "drive-2")
                ));
        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.of(linkedProductImage));
        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.of(linkedProductImage));
        when(productImageRepository.save(any(ProductImage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        YagaArchiveImagesResponse response =
                service.archiveImages(10L);

        assertThat(response.alreadyLinkedImageCount()).isEqualTo(1);
        assertThat(response.archivedImageCount()).isEqualTo(1);
        assertThat(missingListingImage.getProductImage()
                .getDriveFileId()).isEqualTo("drive-2");
        assertThat(missingListingImage.getProductImage()
                .getDisplayOrder()).isEqualTo(1);
        assertThat(response.images())
                .extracting("externalImageId")
                .containsExactly("external-1", "external-2");

        verify(imageDownloader).downloadAll(argThat(images ->
                images.size() == 1 &&
                        images.getFirst().id().equals("external-2")
        ));
    }

    @Test
    void keepsOperationIdempotentWhenAllImagesAreLinked() {
        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        MarketplaceListingImage listingImage =
                listingImage("external-1", 0);
        listingImage.setProductImage(
                new ProductImage(product, "drive-1")
        );
        listing.addImage(listingImage);

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));

        YagaArchiveImagesResponse response =
                service.archiveImages(10L);

        assertThat(response.alreadyLinkedImageCount()).isEqualTo(1);
        assertThat(response.archivedImageCount()).isZero();

        verifyNoInteractions(imageDownloader);
        verifyNoInteractions(driveArchiveStorage);
    }

    @Test
    void deletesUnusedUploadedFilesAfterConcurrentLink()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing snapshot = listing(product);
        snapshot.addImage(listingImage("external-1", 0));

        MarketplaceListing lockedListing = listing(product);
        MarketplaceListingImage alreadyLinkedImage =
                listingImage("external-1", 0);
        alreadyLinkedImage.setProductImage(
                new ProductImage(product, "other-drive")
        );
        lockedListing.addImage(alreadyLinkedImage);

        Path path = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );

        List<DownloadedYagaImage> downloadedImages =
                List.of(downloaded("external-1", path));

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(snapshot));
        when(listingRepository.findByIdWithImagesForUpdate(10L))
                .thenReturn(Optional.of(lockedListing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenReturn(List.of(
                        archived("external-1", "fresh-drive")
                ));
        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.empty());
        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.of(
                        new ProductImage(product, "other-drive")
                ));

        YagaArchiveImagesResponse response =
                service.archiveImages(10L);

        assertThat(response.alreadyLinkedImageCount()).isEqualTo(1);
        assertThat(response.archivedImageCount()).isZero();
        assertThat(Files.notExists(path)).isTrue();

        verify(driveArchiveStorage)
                .deleteCreatedFiles(List.of("fresh-drive"));
        verify(productImageRepository, never())
                .save(any(ProductImage.class));
    }

    @Test
    void deletesCreatedDriveFilesWhenDatabaseSaveFails()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        listing.addImage(listingImage("external-1", 0));

        Path path = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        List<DownloadedYagaImage> downloadedImages =
                List.of(downloaded("external-1", path));

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findByIdWithImagesForUpdate(10L))
                .thenReturn(Optional.of(listing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenReturn(List.of(
                        archived("external-1", "drive-1")
                ));
        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.empty());
        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.empty());
        when(productImageRepository.save(any(ProductImage.class)))
                .thenThrow(
                        new IllegalStateException("database failed")
                );

        assertThatThrownBy(() -> service.archiveImages(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failed");

        assertThat(Files.notExists(path)).isTrue();
        verify(driveArchiveStorage)
                .deleteCreatedFiles(List.of("drive-1"));
    }

    @Test
    void closesDownloadedImagesWhenUploadFails()
            throws Exception {

        YagaImageArchiveService service = service();
        Product product = product();
        MarketplaceListing listing = listing(product);
        listing.addImage(listingImage("external-1", 0));

        Path path = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        List<DownloadedYagaImage> downloadedImages =
                List.of(downloaded("external-1", path));

        when(listingRepository.findByIdWithImages(10L))
                .thenReturn(Optional.of(listing));
        when(imageDownloader.downloadAll(any()))
                .thenReturn(downloadedImages);
        when(driveArchiveStorage.uploadYagaImages(
                eq("RP-000001"),
                eq(10L),
                eq(downloadedImages)
        ))
                .thenThrow(
                        new GoogleDriveAccessException("upload failed")
                );

        assertThatThrownBy(() -> service.archiveImages(10L))
                .isInstanceOf(GoogleDriveAccessException.class)
                .hasMessage("upload failed");

        assertThat(Files.notExists(path)).isTrue();
        verify(listingRepository, never())
                .findByIdWithImagesForUpdate(10L);
    }

    private YagaImageArchiveService service() {
        when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        ))
                .thenAnswer(invocation ->
                        new SimpleTransactionStatus()
                );

        return new YagaImageArchiveService(
                listingRepository,
                productImageRepository,
                imageDownloader,
                driveArchiveStorage,
                transactionManager
        );
    }

    private Product product() {
        Product product = new Product(
                "RP-000001",
                "Imported product"
        );
        product.setId(1L);

        return product;
    }

    private MarketplaceListing listing(Product product) {
        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        "external-listing",
                        "https://www.yaga.ee/shop/toode/item"
                );
        listing.setId(10L);

        return listing;
    }

    private MarketplaceListingImage listingImage(
            String externalImageId,
            int displayOrder
    ) {
        return new MarketplaceListingImage(
                externalImageId,
                "https://images.yaga.ee/" + externalImageId + ".jpg",
                externalImageId + ".jpg",
                displayOrder
        );
    }

    private DownloadedYagaImage downloaded(
            String externalImageId,
            Path path
    ) {
        return new DownloadedYagaImage(
                externalImageId,
                "https://images.yaga.ee/" + externalImageId + ".jpg",
                externalImageId + ".jpg",
                "image/jpeg",
                3,
                path
        );
    }

    private ArchivedDriveFile archived(
            String externalImageId,
            String driveFileId
    ) {
        return new ArchivedDriveFile(
                externalImageId,
                "https://images.yaga.ee/" + externalImageId + ".jpg",
                externalImageId + ".jpg",
                driveFileId
        );
    }
}
