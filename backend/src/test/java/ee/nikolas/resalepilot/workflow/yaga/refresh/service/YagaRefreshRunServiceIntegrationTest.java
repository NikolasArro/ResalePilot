package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "yaga.refresh.batch-size=10",
        "yaga.refresh.max-batch-size=20"
})
@Testcontainers
class YagaRefreshRunServiceIntegrationTest {

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
    private YagaRefreshRunService service;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private YagaRefreshRunRepository runRepository;

    @Autowired
    private YagaRefreshJobRepository jobRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        productImageRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    @Test
    void usesDefaultBatchSizeAndSelectsOldestCurrentPublishedYagaListings() {
        MarketplaceListing newest = eligibleListing(
                "BOOK-RF-001",
                "newest",
                Instant.parse("2026-01-03T00:00:00Z")
        );
        MarketplaceListing oldest = eligibleListing(
                "BOOK-RF-002",
                "oldest",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        MarketplaceListing middle = eligibleListing(
                "BOOK-RF-003",
                "middle",
                Instant.parse("2026-01-02T00:00:00Z")
        );

        YagaRefreshRunResponse response =
                service.startManualDryRun(request(null, "default-size"));

        assertThat(response.requestedBatchSize()).isEqualTo(10);
        assertThat(response.status())
                .isEqualTo(YagaRefreshRunStatus.AWAITING_CONFIRMATION);
        assertThat(response.selectedJobCount()).isEqualTo(3);
        assertThat(response.candidates())
                .extracting("oldListingId")
                .containsExactly(
                        oldest.getId(),
                        middle.getId(),
                        newest.getId()
                );
        assertThat(response.candidates())
                .extracting("status")
                .containsOnly(YagaRefreshJobStatus.SELECTED);
    }

    @Test
    void storesImmutableRefreshJobSnapshot() {
        MarketplaceListing listing = eligibleListing(
                "BOOK-RF-020",
                "snapshot",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        YagaRefreshRunResponse response =
                service.startManualDryRun(request(1, "snapshot"));

        assertThat(response.candidates()).hasSize(1);
        var job = response.candidates().getFirst();
        assertThat(job.productId()).isEqualTo(listing.getProduct().getId());
        assertThat(job.productTitle()).isEqualTo("Refresh candidate");
        assertThat(job.oldListingId()).isEqualTo(listing.getId());
        assertThat(job.oldExternalListingId())
                .isEqualTo("external-snapshot");
        assertThat(job.oldShopSlug()).isEqualTo("nik-ar");
        assertThat(job.oldProductSlug()).isEqualTo("snapshot");
        assertThat(job.oldProductUrl())
                .isEqualTo("https://www.yaga.ee/nik-ar/toode/snapshot");
        assertThat(job.selectedExternalCreatedAt())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(job.selectedListingCreatedAt()).isNotNull();
        assertThat(job.expectedProductImageCount()).isEqualTo(1);
        assertThat(job.expectedMarketplaceListingImageCount()).isEqualTo(1);
    }

    @Test
    void respectsBatchSizeOverrides() {
        eligibleListing("BOOK-RF-004", "one", Instant.parse("2026-01-01T00:00:00Z"));
        eligibleListing("BOOK-RF-005", "two", Instant.parse("2026-01-02T00:00:00Z"));
        eligibleListing("BOOK-RF-006", "three", Instant.parse("2026-01-03T00:00:00Z"));

        assertThat(service.startManualDryRun(request(1, "override-1"))
                .selectedJobCount())
                .isEqualTo(1);

        assertThat(service.startManualDryRun(request(2, "override-2"))
                .selectedJobCount())
                .isEqualTo(2);
    }

    @Test
    void rejectsInvalidBatchSizes() {
        assertThatThrownBy(() ->
                service.startManualDryRun(request(0, "bad-zero")))
                .isInstanceOf(YagaRefreshRequestInvalidException.class)
                .hasMessageContaining("at least 1");

        assertThatThrownBy(() ->
                service.startManualDryRun(request(21, "bad-too-large")))
                .isInstanceOf(YagaRefreshRequestInvalidException.class)
                .hasMessageContaining("maxBatchSize");
    }

    @Test
    void excludesIneligibleListingsAndProducts() {
        MarketplaceListing eligible = eligibleListing(
                "BOOK-RF-007",
                "eligible",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        listing("BOOK-RF-008", "hidden", Marketplace.YAGA,
                MarketplaceListingStatus.HIDDEN, true, ProductStatus.DRAFT,
                true, true);
        listing("BOOK-RF-009", "non-current", Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED, false, ProductStatus.DRAFT,
                true, true);
        listing("BOOK-RF-011", "archived-product", Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED, true, ProductStatus.ARCHIVED,
                true, true);
        listing("BOOK-RF-012", "no-product-image", Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED, true, ProductStatus.DRAFT,
                false, true);
        listing("BOOK-RF-013", "unlinked-listing-image", Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED, true, ProductStatus.DRAFT,
                true, false);
        MarketplaceListing hiddenAt = eligibleListing(
                "BOOK-RF-021",
                "hidden-at",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        hiddenAt.setHiddenAt(Instant.parse("2026-02-01T00:00:00Z"));
        listingRepository.saveAndFlush(hiddenAt);
        MarketplaceListing deletedAt = eligibleListing(
                "BOOK-RF-022",
                "deleted-at",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        deletedAt.setDeletedAt(Instant.parse("2026-02-01T00:00:00Z"));
        listingRepository.saveAndFlush(deletedAt);

        YagaRefreshRunResponse response =
                service.startManualDryRun(request(10, "eligible-only"));

        assertThat(response.candidates())
                .extracting("oldListingId")
                .containsExactly(eligible.getId());
    }

    @Test
    void usesCreatedAtAsFallbackWhenExternalCreatedAtIsMissing() {
        MarketplaceListing withExternal = eligibleListing(
                "BOOK-RF-023",
                "with-external",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        MarketplaceListing withoutExternal = eligibleListing(
                "BOOK-RF-024",
                "without-external",
                null
        );

        YagaRefreshRunResponse response =
                service.startManualDryRun(request(10, "fallback-order"));

        assertThat(response.candidates())
                .extracting("oldListingId")
                .containsExactly(withExternal.getId(), withoutExternal.getId());
        assertThat(response.candidates().get(1).orderingTimestamp())
                .isEqualTo(withoutExternal.getCreatedAt());
    }

    @Test
    void activeJobExcludesProductFromNextRun() {
        MarketplaceListing listing = eligibleListing(
                "BOOK-RF-014",
                "active",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        YagaRefreshRunResponse first =
                service.startManualDryRun(request(10, "active-first"));
        YagaRefreshRunResponse second =
                service.startManualDryRun(request(10, "active-second"));

        assertThat(first.candidates())
                .extracting("oldListingId")
                .containsExactly(listing.getId());
        assertThat(second.selectedJobCount()).isZero();
    }

    @Test
    void stableTieBreakerUsesListingId() {
        MarketplaceListing first = eligibleListing(
                "BOOK-RF-015",
                "tie-one",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        MarketplaceListing second = eligibleListing(
                "BOOK-RF-016",
                "tie-two",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        YagaRefreshRunResponse response =
                service.startManualDryRun(request(10, "tie"));

        assertThat(response.candidates())
                .extracting("oldListingId")
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void sameIdempotencyKeyReturnsSameRun() {
        eligibleListing("BOOK-RF-017", "idempotent", Instant.parse("2026-01-01T00:00:00Z"));

        YagaRefreshRunResponse first =
                service.startManualDryRun(request(10, "same-key"));
        YagaRefreshRunResponse second =
                service.startManualDryRun(request(10, "same-key"));

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(runRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void cancelIsAllowedOnlyWhileAwaitingConfirmation() {
        eligibleListing("BOOK-RF-025", "cancel", Instant.parse("2026-01-01T00:00:00Z"));
        YagaRefreshRunResponse created =
                service.startManualDryRun(request(10, "cancel"));

        YagaRefreshRunResponse cancelled =
                service.cancelRun(created.runId());

        assertThat(cancelled.status())
                .isEqualTo(YagaRefreshRunStatus.CANCELLED);

        assertThatThrownBy(() -> service.cancelRun(created.runId()))
                .isInstanceOf(YagaRefreshInvalidStateException.class);
    }

    @Test
    void getRunWorksAfterClearingPersistenceContext() {
        eligibleListing("BOOK-RF-018", "get-run", Instant.parse("2026-01-01T00:00:00Z"));
        YagaRefreshRunResponse created =
                service.startManualDryRun(request(10, "get-run"));

        entityManager.clear();

        YagaRefreshRunResponse loaded = service.getRun(created.runId());

        assertThat(loaded.runId()).isEqualTo(created.runId());
        assertThat(loaded.candidates()).hasSize(1);
        assertThat(loaded.candidates().getFirst().sku())
                .isEqualTo("BOOK-RF-018");
    }

    @Test
    void concurrentRunsDoNotSelectSameProduct() throws Exception {
        eligibleListing("BOOK-RF-019", "concurrent", Instant.parse("2026-01-01T00:00:00Z"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var futures = Stream.of("con-a", "con-b")
                    .map(key -> executor.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return service.startManualDryRun(request(10, key));
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<YagaRefreshRunResponse> responses = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertThat(responses.stream()
                    .mapToInt(YagaRefreshRunResponse::selectedJobCount)
                    .sum())
                    .isEqualTo(1);
            assertThat(jobRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private YagaRefreshRunRequest request(
            Integer batchSize,
            String idempotencyKey
    ) {
        return new YagaRefreshRunRequest(
                batchSize,
                YagaRefreshMode.MANUAL,
                idempotencyKey
        );
    }

    private MarketplaceListing eligibleListing(
            String sku,
            String slug,
            Instant orderingTimestamp
    ) {
        return listing(
                sku,
                slug,
                Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED,
                true,
                ProductStatus.DRAFT,
                true,
                true,
                orderingTimestamp
        );
    }

    private MarketplaceListing listing(
            String sku,
            String slug,
            Marketplace marketplace,
            MarketplaceListingStatus listingStatus,
            boolean current,
            ProductStatus productStatus,
            boolean withProductImage,
            boolean linkedListingImage
    ) {
        return listing(
                sku,
                slug,
                marketplace,
                listingStatus,
                current,
                productStatus,
                withProductImage,
                linkedListingImage,
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private MarketplaceListing listing(
            String sku,
            String slug,
            Marketplace marketplace,
            MarketplaceListingStatus listingStatus,
            boolean current,
            ProductStatus productStatus,
            boolean withProductImage,
            boolean linkedListingImage,
            Instant orderingTimestamp
    ) {
        Product product = new Product(sku, "Refresh candidate");
        product.setStatus(productStatus);
        Product savedProduct = productRepository.saveAndFlush(product);

        ProductImage productImage = null;
        if (withProductImage) {
            productImage = new ProductImage(savedProduct, "drive-" + sku);
            productImage.setFileName(slug + ".jpg");
            productImage.setDisplayOrder(0);
            productImage.setPrimaryImage(true);
            productImage = productImageRepository.saveAndFlush(productImage);
        }

        MarketplaceListing listing = new MarketplaceListing(
                savedProduct,
                marketplace,
                "external-" + slug,
                "https://www.yaga.ee/nik-ar/toode/" + slug
        );
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(slug);
        listing.setStatus(listingStatus);
        listing.setCurrent(current);
        listing.setExternalCreatedAt(orderingTimestamp);
        listing.addCategory(new MarketplaceListingCategory(
                0,
                1L,
                null,
                "Raamatud"
        ));
        MarketplaceListingImage listingImage =
                new MarketplaceListingImage(
                        "image-" + slug,
                        "https://images.yaga.ee/" + slug + ".jpg",
                        slug + ".jpg",
                        0
                );
        if (linkedListingImage) {
            listingImage.setProductImage(productImage);
        }
        listing.addImage(listingImage);
        return listingRepository.saveAndFlush(listing);
    }
}
