package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.entity.Marketplace;
import ee.nikolas.resalepilot.entity.MarketplaceListing;
import ee.nikolas.resalepilot.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.repository.ProductImageRepository;
import ee.nikolas.resalepilot.repository.ProductRepository;
import ee.nikolas.resalepilot.yaga.DownloadedYagaImage;
import ee.nikolas.resalepilot.yaga.YagaImageDownloader;
import ee.nikolas.resalepilot.yaga.YagaImportedProductData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class YagaImageArchiveServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("resalepilot")
                    .withUsername("resalepilot")
                    .withPassword("resalepilot");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private YagaImageArchiveService archiveService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private FakeYagaImageDownloader imageDownloader;

    @Autowired
    private FakeDriveArchiveStorage driveArchiveStorage;

    @Test
    void repeatedArchiveAfterClearingPersistenceContextIsIdempotent() {
        Product product =
                productRepository.saveAndFlush(
                        new Product(
                                "RP-INTEGRATION-001",
                                "Integration book"
                        )
                );

        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        "external-integration-001",
                        "https://www.yaga.ee/shop/toode/item"
                );

        listing.addImage(
                listingImage("external-1", 0)
        );
        listing.addImage(
                listingImage("external-2", 1)
        );

        MarketplaceListing savedListing =
                listingRepository.saveAndFlush(listing);

        YagaArchiveImagesResponse firstResponse =
                archiveService.archiveImages(savedListing.getId());

        assertThat(firstResponse.alreadyLinkedImageCount())
                .isZero();
        assertThat(firstResponse.archivedImageCount())
                .isEqualTo(2);
        assertThat(firstResponse.totalImageCount())
                .isEqualTo(2);
        assertThat(firstResponse.images())
                .extracting("driveFileId")
                .containsExactly(
                        "drive-file-1",
                        "drive-file-2"
                );
        assertThat(firstResponse.images())
                .extracting("displayOrder")
                .containsExactly(0, 1);
        assertThat(firstResponse.images())
                .extracting("primary")
                .containsExactly(true, false);

        entityManager.clear();
        imageDownloader.reset();
        driveArchiveStorage.reset();

        YagaArchiveImagesResponse secondResponse =
                archiveService.archiveImages(savedListing.getId());

        assertThat(secondResponse.alreadyLinkedImageCount())
                .isEqualTo(2);
        assertThat(secondResponse.archivedImageCount())
                .isZero();
        assertThat(secondResponse.totalImageCount())
                .isEqualTo(2);
        assertThat(secondResponse.images())
                .extracting("driveFileId")
                .containsExactly(
                        "drive-file-1",
                        "drive-file-2"
                );
        assertThat(secondResponse.images())
                .extracting("displayOrder")
                .containsExactly(0, 1);
        assertThat(secondResponse.images())
                .extracting("primary")
                .containsExactly(true, false);

        assertThat(productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(
                        product.getId()
                ))
                .hasSize(2);
        assertThat(imageDownloader.downloadAllCalls())
                .isZero();
        assertThat(driveArchiveStorage.uploadCalls())
                .isZero();
        assertThat(driveArchiveStorage.verifyAvailableCalls())
                .isZero();
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

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        FakeYagaImageDownloader fakeYagaImageDownloader() {
            return new FakeYagaImageDownloader();
        }

        @Bean
        @Primary
        FakeDriveArchiveStorage fakeDriveArchiveStorage() {
            return new FakeDriveArchiveStorage();
        }
    }

    static class FakeYagaImageDownloader
            extends YagaImageDownloader {

        private final AtomicInteger downloadAllCalls =
                new AtomicInteger();

        FakeYagaImageDownloader() {
            super(new ee.nikolas.resalepilot.config.YagaImageProperties(
                    6,
                    26214400,
                    104857600
            ));
        }

        @Override
        public List<DownloadedYagaImage> downloadAll(
                List<YagaImportedProductData.Image> images
        ) {
            downloadAllCalls.incrementAndGet();
            List<DownloadedYagaImage> result =
                    new ArrayList<>();

            for (YagaImportedProductData.Image image : images) {
                try {
                    result.add(
                            new DownloadedYagaImage(
                                    image.id(),
                                    image.originalUrl(),
                                    image.fileName(),
                                    "image/jpeg",
                                    3,
                                    Files.createTempFile(
                                            "resalepilot-integration-",
                                            ".jpg"
                                    )
                            )
                    );

                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }

            return List.copyOf(result);
        }

        int downloadAllCalls() {
            return downloadAllCalls.get();
        }

        void reset() {
            downloadAllCalls.set(0);
        }
    }

    static class FakeDriveArchiveStorage
            implements DriveArchiveStorage {

        private final AtomicInteger verifyAvailableCalls =
                new AtomicInteger();
        private final AtomicInteger uploadCalls =
                new AtomicInteger();

        @Override
        public void verifyAvailable() {
            verifyAvailableCalls.incrementAndGet();
        }

        @Override
        public List<ArchivedDriveFile> uploadYagaImages(
                String productSku,
                Long marketplaceListingId,
                List<DownloadedYagaImage> images
        ) {
            uploadCalls.incrementAndGet();

            List<ArchivedDriveFile> result =
                    new ArrayList<>();

            for (int index = 0; index < images.size(); index++) {
                DownloadedYagaImage image = images.get(index);

                result.add(
                        new ArchivedDriveFile(
                                image.externalImageId(),
                                image.sourceUrl(),
                                "%s-yaga-%d-%02d.jpg".formatted(
                                        productSku,
                                        marketplaceListingId,
                                        index + 1
                                ),
                                "drive-file-" + (index + 1)
                        )
                );
            }

            return List.copyOf(result);
        }

        @Override
        public void deleteCreatedFiles(
                List<String> driveFileIds
        ) {
        }

        int verifyAvailableCalls() {
            return verifyAvailableCalls.get();
        }

        int uploadCalls() {
            return uploadCalls.get();
        }

        void reset() {
            verifyAvailableCalls.set(0);
            uploadCalls.set(0);
        }
    }
}
