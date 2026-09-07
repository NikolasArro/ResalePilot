package ee.nikolas.resalepilot.workflow.yaga.batcharchive.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveService;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveException;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveFailureCode;
import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchiveRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJob;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRun;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception.YagaBatchArchiveInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception.YagaBatchArchiveRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository.YagaBatchArchiveJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository.YagaBatchArchiveRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "yaga.batch-archive.enabled=true",
        "yaga.batch-archive.default-max-listings=2",
        "yaga.batch-archive.max-listings=5"
})
@Testcontainers
class YagaBatchArchiveServiceIntegrationTest {

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
    private YagaBatchArchiveService service;

    @Autowired
    private YagaImageArchiveService archiveService;

    @Autowired
    private YagaBatchArchiveRunRepository runRepository;

    @Autowired
    private YagaBatchArchiveJobRepository jobRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabaseAndMocks() {
        jobRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        productImageRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        Mockito.reset(archiveService);
    }

    @Test
    void prepareSelectsEligibleListingsInStableOrderAndDoesNotArchive() {
        MarketplaceListing newest = listing("NEWEST", "newest",
                Instant.parse("2026-01-03T00:00:00Z"), 2, 0);
        MarketplaceListing oldest = listing("OLDEST", "oldest",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        MarketplaceListing tie = listing("TIE", "tie",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        listing("HIDDEN", "hidden",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0)
                .setStatus(MarketplaceListingStatus.HIDDEN);
        listing("NOIMG", "noimg",
                Instant.parse("2026-01-01T00:00:00Z"), 0, 0);
        MarketplaceListing linked = listing("LINKED", "linked",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 2);
        linked.getProduct().setStatus(ProductStatus.ARCHIVED);
        productRepository.saveAndFlush(linked.getProduct());

        YagaBatchArchiveRunResponse response =
                service.prepare(2, "prepare-order");

        assertThat(response.status())
                .isEqualTo(YagaBatchArchiveRunStatus.AWAITING_CONFIRMATION);
        assertThat(response.requestedMaxListings()).isEqualTo(2);
        assertThat(response.selectedJobCount()).isEqualTo(2);
        assertThat(response.jobs())
                .extracting("listingId")
                .containsExactly(oldest.getId(), tie.getId());
        assertThat(response.jobs())
                .extracting("productTitle")
                .containsExactly("Product OLDEST", "Product TIE");
        assertThat(response.jobs())
                .extracting("expectedImageCount")
                .containsExactly(2, 2);
        assertThat(response.jobs())
                .extracting("initiallyLinkedImageCount")
                .containsExactly(0, 0);
        verify(archiveService, never()).archiveImages(Mockito.anyLong());
        assertThat(newest.getId()).isNotNull();
    }

    @Test
    void defaultMaxListingsAndSameIdempotencyKeyReturnSameRun() {
        listing("A", "a", Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        listing("B", "b", Instant.parse("2026-01-02T00:00:00Z"), 2, 0);
        listing("C", "c", Instant.parse("2026-01-03T00:00:00Z"), 2, 0);

        YagaBatchArchiveRunResponse first =
                service.prepare(null, "same-key");
        YagaBatchArchiveRunResponse second =
                service.prepare(5, "same-key");

        assertThat(first.requestedMaxListings()).isEqualTo(2);
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.jobs()).hasSize(2);
        assertThat(runRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidMaxListingsAndPhraseAreRejected() {
        assertThatThrownBy(() -> service.prepare(0, "zero"))
                .isInstanceOf(YagaBatchArchiveRequestInvalidException.class);
        assertThatThrownBy(() -> service.prepare(6, "too-large"))
                .isInstanceOf(YagaBatchArchiveRequestInvalidException.class);
        assertThatThrownBy(() -> service.prepare(1, " "))
                .isInstanceOf(YagaBatchArchiveRequestInvalidException.class);

        listing("A", "a", Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        YagaBatchArchiveRunResponse run =
                service.prepare(1, "bad-phrase");

        assertThatThrownBy(() -> service.confirm(run.runId(), "YES"))
                .isInstanceOf(YagaBatchArchiveRequestInvalidException.class);
        verify(archiveService, never()).archiveImages(Mockito.anyLong());
    }

    @Test
    void confirmArchivesEachJobIndependentlyAndKeepsSuccessfulResult() {
        MarketplaceListing first = listing("FIRST", "first",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        MarketplaceListing second = listing("SECOND", "second",
                Instant.parse("2026-01-02T00:00:00Z"), 2, 0);
        when(archiveService.archiveImages(first.getId()))
                .thenReturn(response(first.getId(), 0, 2, 2));
        when(archiveService.archiveImages(second.getId()))
                .thenThrow(new IllegalStateException("Drive unavailable"));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(2, "partial-failure");
        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.status())
                .isEqualTo(YagaBatchArchiveRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(confirmed.archivedListingCount()).isEqualTo(1);
        assertThat(confirmed.failedListingCount()).isEqualTo(1);
        assertThat(confirmed.jobs())
                .extracting("status")
                .containsExactly(
                        YagaBatchArchiveJobStatus.ARCHIVED,
                        YagaBatchArchiveJobStatus.FAILED
                );
        assertThat(confirmed.jobs().get(1).lastErrorCode())
                .isEqualTo("ARCHIVE_RESULT_UNKNOWN");
        assertThat(confirmed.jobs().get(1).failedImageCount())
                .isZero();
    }

    @Test
    void typedDownloadFailureIsStoredWithSafeCodeAndUnknownImageCount() {
        MarketplaceListing listing = listing("DOWNLOAD", "download",
                Instant.parse("2026-01-01T00:00:00Z"), 5, 0);
        when(archiveService.archiveImages(listing.getId()))
                .thenThrow(new YagaImageArchiveException(
                        YagaImageArchiveFailureCode.IMAGE_DOWNLOAD_FAILED,
                        "Image download failed before Drive upload; " +
                                "firstMissingDisplayOrder=0",
                        null,
                        new IllegalStateException("download")
                ));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "download-failure");
        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.jobs().getFirst().status())
                .isEqualTo(YagaBatchArchiveJobStatus.FAILED);
        assertThat(confirmed.jobs().getFirst().lastErrorCode())
                .isEqualTo("IMAGE_DOWNLOAD_FAILED");
        assertThat(confirmed.jobs().getFirst().lastSafeErrorMessage())
                .contains("firstMissingDisplayOrder=0")
                .doesNotContain("https://");
        assertThat(confirmed.jobs().getFirst().failedImageCount())
                .isZero();
    }

    @Test
    void typedDriveFailureIsStoredWithSafeCodeAndUnknownImageCount() {
        MarketplaceListing listing = listing("DRIVE", "drive",
                Instant.parse("2026-01-01T00:00:00Z"), 5, 0);
        when(archiveService.archiveImages(listing.getId()))
                .thenThrow(new YagaImageArchiveException(
                        YagaImageArchiveFailureCode.DRIVE_UPLOAD_FAILED,
                        "Drive upload failed for Yaga image number 2 of 5",
                        null,
                        new IllegalStateException("drive")
                ));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "drive-failure");
        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.jobs().getFirst().lastErrorCode())
                .isEqualTo("DRIVE_UPLOAD_FAILED");
        assertThat(confirmed.jobs().getFirst().lastSafeErrorMessage())
                .isEqualTo("Drive upload failed for Yaga image number 2 of 5");
        assertThat(confirmed.jobs().getFirst().failedImageCount())
                .isZero();
    }

    @Test
    void typedDatabaseLinkFailureIsStoredWithoutRetryingOrDeleting() {
        MarketplaceListing listing = listing("DBLINK", "dblink",
                Instant.parse("2026-01-01T00:00:00Z"), 5, 0);
        when(archiveService.archiveImages(listing.getId()))
                .thenThrow(new YagaImageArchiveException(
                        YagaImageArchiveFailureCode.DATABASE_LINK_FAILED,
                        "Database link failed after Drive upload; " +
                                "firstMissingDisplayOrder=0; " +
                                "uploadedDriveFileIdsNotExposed=true",
                        null,
                        new IllegalStateException("db")
                ));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "db-link-failure");
        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");
        YagaBatchArchiveRunResponse repeated =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.jobs().getFirst().lastErrorCode())
                .isEqualTo("DATABASE_LINK_FAILED");
        assertThat(confirmed.jobs().getFirst().lastSafeErrorMessage())
                .contains("uploadedDriveFileIdsNotExposed=true")
                .doesNotContain("drive-");
        assertThat(confirmed.jobs().getFirst().failedImageCount())
                .isZero();
        verify(archiveService, Mockito.times(1))
                .archiveImages(listing.getId());
        assertThat(repeated.jobs().getFirst().lastErrorCode())
                .isEqualTo("DATABASE_LINK_FAILED");
    }

    @Test
    void fullyLinkedBeforeConfirmBecomesAlreadyArchivedWithoutArchiveCall() {
        MarketplaceListing listing = listing("PARTIAL", "partial",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 1);
        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "already-before-confirm");

        MarketplaceListing reloaded = listingRepository
                .findByIdWithImagesAndProductImages(listing.getId())
                .orElseThrow();
        ProductImage secondImage = productImage(reloaded.getProduct(), 1);
        reloaded.getImages().get(1).setProductImage(secondImage);
        listingRepository.saveAndFlush(reloaded);
        entityManager.clear();

        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.alreadyArchivedListingCount()).isEqualTo(1);
        assertThat(confirmed.jobs().getFirst().status())
                .isEqualTo(YagaBatchArchiveJobStatus.ALREADY_ARCHIVED);
        assertThat(confirmed.jobs().getFirst().alreadyLinkedImageCount())
                .isEqualTo(2);
        verify(archiveService, never()).archiveImages(Mockito.anyLong());
    }

    @Test
    void partialListingCallsSingleArchiveServiceAndStoresResponseCounts() {
        MarketplaceListing listing = listing("PARTIAL", "partial",
                Instant.parse("2026-01-01T00:00:00Z"), 3, 1);
        when(archiveService.archiveImages(listing.getId()))
                .thenReturn(response(listing.getId(), 1, 2, 3));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "partial");
        YagaBatchArchiveRunResponse confirmed =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(confirmed.archivedListingCount()).isEqualTo(1);
        assertThat(confirmed.jobs().getFirst().status())
                .isEqualTo(YagaBatchArchiveJobStatus.ARCHIVED);
        assertThat(confirmed.jobs().getFirst().alreadyLinkedImageCount())
                .isEqualTo(1);
        assertThat(confirmed.jobs().getFirst().archivedImageCount())
                .isEqualTo(2);
        verify(archiveService).archiveImages(listing.getId());
    }

    @Test
    void terminalConfirmDoesNotArchiveAgain() {
        MarketplaceListing listing = listing("ONE", "one",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        when(archiveService.archiveImages(listing.getId()))
                .thenReturn(response(listing.getId(), 0, 2, 2));

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "terminal");
        service.confirm(prepared.runId(), "ARCHIVE");
        YagaBatchArchiveRunResponse second =
                service.confirm(prepared.runId(), "ARCHIVE");

        assertThat(second.archivedListingCount()).isEqualTo(1);
        verify(archiveService, Mockito.times(1))
                .archiveImages(listing.getId());
    }

    @Test
    void activeJobGuardExcludesListingFromNewPreparation() {
        MarketplaceListing listing = listing("ACTIVE", "active",
                Instant.parse("2026-01-01T00:00:00Z"), 2, 0);
        YagaBatchArchiveRun run = new YagaBatchArchiveRun(
                1,
                "existing-active",
                Instant.parse("2026-01-02T00:00:00Z")
        );
        run.setStatus(YagaBatchArchiveRunStatus.AWAITING_CONFIRMATION);
        run.setSelectedJobCount(1);
        run.addJob(new YagaBatchArchiveJob(
                listing.getProduct(),
                listing,
                0,
                2,
                0,
                Instant.parse("2026-01-02T00:00:00Z")
        ));
        runRepository.saveAndFlush(run);
        entityManager.clear();

        YagaBatchArchiveRunResponse response =
                service.prepare(1, "guard");

        assertThat(response.jobs()).isEmpty();
        assertThat(response.selectedJobCount()).isZero();
    }

    @Test
    void getWorksAfterClearingPersistenceContextAndCancelBeforeConfirm() {
        listing("A", "a", Instant.parse("2026-01-01T00:00:00Z"), 2, 0);

        YagaBatchArchiveRunResponse prepared =
                service.prepare(1, "get-cancel");
        entityManager.clear();

        assertThat(service.get(prepared.runId()).jobs()).hasSize(1);
        YagaBatchArchiveRunResponse cancelled =
                service.cancel(prepared.runId());
        assertThat(cancelled.status())
                .isEqualTo(YagaBatchArchiveRunStatus.CANCELLED);
        assertThatThrownBy(() -> service.cancel(prepared.runId()))
                .isInstanceOf(YagaBatchArchiveInvalidStateException.class);
    }

    @Test
    void productAndListingImageCountsDoNotDuplicateWhenAlreadyArchived() {
        listing("LINKED", "linked",
                Instant.parse("2026-01-01T00:00:00Z"), 4, 4);

        YagaBatchArchiveRunResponse response =
                service.prepare(1, "fully-linked");

        assertThat(response.jobs()).isEmpty();
        assertThat(productImageRepository.count()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from marketplace_listing_images",
                Long.class
        )).isEqualTo(4L);
        verify(archiveService, never()).archiveImages(Mockito.anyLong());
    }

    @Test
    void workflowDoesNotImportDriveOrPlaywrightImplementation() throws Exception {
        Path root = Path.of(
                "src/main/java/ee/nikolas/resalepilot/workflow/yaga/batcharchive"
        );
        List<String> source = Files.walk(root)
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();

        assertThat(source)
                .noneMatch(content ->
                        content.contains("Playwright") ||
                                content.contains("YagaPageDataClient") ||
                                content.contains("GoogleDrive") ||
                                content.contains("DriveArchiveStorage"));
    }

    private MarketplaceListing listing(
            String sku,
            String productSlug,
            Instant externalCreatedAt,
            int imageCount,
            int linkedImageCount
    ) {
        Product product = productRepository.saveAndFlush(
                new Product(sku, "Product " + sku)
        );
        product.setStatus(ProductStatus.LISTED);
        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                "EXT-" + sku,
                "https://www.yaga.ee/nik-ar/toode/" + productSlug
        );
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(true);
        listing.setExternalCreatedAt(externalCreatedAt);
        for (int index = 0; index < imageCount; index++) {
            MarketplaceListingImage listingImage =
                    new MarketplaceListingImage(
                            sku + "-image-" + index,
                            "https://images.yaga.ee/" + sku + "-" + index + ".jpg",
                            sku + "-" + index + ".jpg",
                            index
                    );
            if (index < linkedImageCount) {
                listingImage.setProductImage(productImage(product, index));
            }
            listing.addImage(listingImage);
        }
        return listingRepository.saveAndFlush(listing);
    }

    private ProductImage productImage(Product product, int displayOrder) {
        ProductImage image =
                new ProductImage(product, "drive-" + product.getSku() + "-" +
                        displayOrder);
        image.setFileName(product.getSku() + "-" + displayOrder + ".jpg");
        image.setDisplayOrder(displayOrder);
        image.setPrimaryImage(displayOrder == 0);
        return productImageRepository.saveAndFlush(image);
    }

    private YagaArchiveImagesResponse response(
            Long listingId,
            int alreadyLinked,
            int archived,
            int total
    ) {
        return new YagaArchiveImagesResponse(
                listingId,
                alreadyLinked,
                archived,
                total,
                List.of()
        );
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        YagaImageArchiveService archiveService() {
            return Mockito.mock(YagaImageArchiveService.class);
        }
    }
}
