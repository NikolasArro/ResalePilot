package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductImage;

import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.exception.YagaPublicationReconciliationConflictException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.parser.YagaPageDataParser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Transactional
class YagaPublicationReconciliationServiceIntegrationTest {

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
    private ProductImageRepository productImageRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private FakeYagaPageDataClient pageDataClient;
    private YagaPublicationReconciliationService service;

    @BeforeEach
    void setUp() {
        pageDataClient = new FakeYagaPageDataClient();
        service = new YagaPublicationReconciliationService(
                listingRepository,
                productImageRepository,
                pageDataClient,
                transactionManager
        );
    }

    @Test
    void reconcilesPublishedListingWithoutSession() {
        MarketplaceListing oldListing =
                oldListingWithProductImages();
        pageDataClient.setData(importedData(
                "Description",
                new BigDecimal("17.00"),
                List.of("Raamatud", "Ajalugu"),
                4
        ));

        YagaListingPublicationReconcileResponse response =
                service.reconcile(
                        oldListing.getId(),
                        request()
                );

        assertThat(response.status())
                .isEqualTo(YagaPublicationStatus.PUBLISHED);
        assertThat(response.oldListingId())
                .isEqualTo(oldListing.getId());
        assertThat(response.productId())
                .isEqualTo(oldListing.getProduct().getId());
        assertThat(response.newProductUrl())
                .isEqualTo(
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
                );
        assertThat(response.imageCount()).isEqualTo(4);

        entityManager.clear();
        MarketplaceListing saved =
                listingRepository
                        .findByMarketplaceAndShopSlugAndProductSlug(
                                Marketplace.YAGA,
                                "nik-ar",
                                "5u7arpkm6q"
                        )
                        .orElseThrow();

        assertThat(saved.getProduct().getId())
                .isEqualTo(oldListing.getProduct().getId());
        assertThat(saved.isCurrent()).isFalse();
        assertThat(saved.getImages()).hasSize(4);
        assertThat(saved.getImages())
                .extracting(MarketplaceListingImage::getDisplayOrder)
                .containsExactly(0, 1, 2, 3);
        assertThat(saved.getImages())
                .extracting(image ->
                        image.getProductImage().getDriveFileId())
                .containsExactly(
                        "drive-0",
                        "drive-1",
                        "drive-2",
                        "drive-3"
                );
        assertThat(productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(
                        oldListing.getProduct().getId()
                ))
                .hasSize(4);
        assertThat(pageDataClient.calls()).isEqualTo(1);

        MarketplaceListing unchangedOld =
                listingRepository.findById(oldListing.getId())
                        .orElseThrow();
        assertThat(unchangedOld.isCurrent()).isTrue();
        assertThat(unchangedOld.getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
    }

    @Test
    void repeatedReconciliationReturnsExistingListingWithoutDuplicate() {
        MarketplaceListing oldListing =
                oldListingWithProductImages();
        pageDataClient.setData(importedData(
                "Description",
                new BigDecimal("17.00"),
                List.of("Raamatud", "Ajalugu"),
                4
        ));

        YagaListingPublicationReconcileResponse first =
                service.reconcile(oldListing.getId(), request());
        pageDataClient.reset();
        pageDataClient.failWith(new IllegalStateException("Yaga down"));
        YagaListingPublicationReconcileResponse second =
                service.reconcile(oldListing.getId(), request());

        assertThat(second.newListingId())
                .isEqualTo(first.newListingId());
        assertThat(second.imageCount()).isEqualTo(4);
        assertThat(listingRepository
                .findByMarketplaceAndShopSlugAndProductSlug(
                        Marketplace.YAGA,
                        "nik-ar",
                        "5u7arpkm6q"
                ))
                .isPresent();
        assertThat(listingRepository.findAll()
                .stream()
                .filter(listing -> "5u7arpkm6q"
                        .equals(listing.getProductSlug()))
                .count())
                .isEqualTo(1);
        assertThat(pageDataClient.calls()).isZero();
        assertThat(countRows("MarketplaceListing")).isEqualTo(2);
        assertThat(countRows("MarketplaceListingImage")).isEqualTo(8);
        assertThat(productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(
                        oldListing.getProduct().getId()
                ))
                .hasSize(4);
    }

    @Test
    void dataMismatchDoesNotSavePublishedListingOrChangeOldListing() {
        MarketplaceListing oldListing =
                oldListingWithProductImages();
        MarketplaceListingStatus oldStatus = oldListing.getStatus();
        pageDataClient.setData(importedData(
                "Different description",
                new BigDecimal("17.00"),
                List.of("Raamatud", "Ajalugu"),
                4
        ));

        assertThatThrownBy(() ->
                service.reconcile(oldListing.getId(), request())
        )
                .isInstanceOf(
                        YagaPublicationReconciliationConflictException.class
                );

        assertThat(listingRepository
                .findByMarketplaceAndShopSlugAndProductSlug(
                        Marketplace.YAGA,
                        "nik-ar",
                        "5u7arpkm6q"
                ))
                .isEmpty();

        entityManager.clear();
        MarketplaceListing unchanged =
                listingRepository.findById(oldListing.getId())
                        .orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(oldStatus);
        assertThat(unchanged.getHiddenAt()).isNull();
        assertThat(unchanged.getDeletedAt()).isNull();
    }

    @Test
    void existingPublishedListingForDifferentProductConflictsBeforeYagaRead() {
        MarketplaceListing oldListing =
                oldListingWithProductImages();
        MarketplaceListing otherListing =
                existingPublishedListingForOtherProduct();
        long listingCount = countRows("MarketplaceListing");
        long listingImageCount = countRows("MarketplaceListingImage");
        long productImageCount = countRows("ProductImage");
        pageDataClient.failWith(new IllegalStateException("Yaga down"));

        assertThatThrownBy(() ->
                service.reconcile(oldListing.getId(), request())
        )
                .isInstanceOf(
                        YagaPublicationReconciliationConflictException.class
                )
                .hasMessageContaining("different product");

        assertThat(pageDataClient.calls()).isZero();
        assertThat(countRows("MarketplaceListing"))
                .isEqualTo(listingCount);
        assertThat(countRows("MarketplaceListingImage"))
                .isEqualTo(listingImageCount);
        assertThat(countRows("ProductImage"))
                .isEqualTo(productImageCount);
        assertThat(listingRepository.findById(oldListing.getId()))
                .get()
                .extracting(MarketplaceListing::getStatus)
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(listingRepository.findById(otherListing.getId()))
                .isPresent();
    }

    private YagaListingPublicationReconcileRequest request() {
        return new YagaListingPublicationReconcileRequest(
                "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
        );
    }

    private MarketplaceListing oldListingWithProductImages() {
        Product product = new Product("BOOK-REC-001", "Kalevipoeg");
        product.setDescription("Description");
        product.setAskingPrice(new BigDecimal("17.00"));
        product.setCondition(ProductCondition.GOOD);
        Product savedProduct =
                productRepository.saveAndFlush(product);

        MarketplaceListing listing =
                new MarketplaceListing(
                        savedProduct,
                        Marketplace.YAGA,
                        "old-external",
                        "https://www.yaga.ee/nik-ar/toode/old"
                );
        listing.setShopSlug("nik-ar");
        listing.setProductSlug("old");
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setExternalStatus("published");
        listing.addCategory(
                new MarketplaceListingCategory(
                        0,
                        1L,
                        null,
                        "Raamatud"
                )
        );
        listing.addCategory(
                new MarketplaceListingCategory(
                        1,
                        2L,
                        1L,
                        "Ajalugu"
                )
        );

        for (int index = 0; index < 4; index++) {
            ProductImage productImage =
                    productImageRepository.saveAndFlush(
                            productImage(savedProduct, index)
                    );
            MarketplaceListingImage listingImage =
                    new MarketplaceListingImage(
                            "old-image-" + index,
                            "https://images.yaga.ee/old-" + index + ".jpg",
                            "old-" + index + ".jpg",
                            index
                    );
            listingImage.setProductImage(productImage);
            listing.addImage(listingImage);
        }

        return listingRepository.saveAndFlush(listing);
    }

    private ProductImage productImage(
            Product product,
            int displayOrder
    ) {
        ProductImage image =
                new ProductImage(product, "drive-" + displayOrder);
        image.setFileName("image-" + displayOrder + ".jpg");
        image.setDisplayOrder(displayOrder);
        image.setPrimaryImage(displayOrder == 0);
        return image;
    }

    private MarketplaceListing existingPublishedListingForOtherProduct() {
        Product product = new Product("BOOK-REC-OTHER", "Other book");
        product.setDescription("Description");
        product.setAskingPrice(new BigDecimal("17.00"));
        product.setCondition(ProductCondition.GOOD);
        Product savedProduct =
                productRepository.saveAndFlush(product);

        MarketplaceListing listing =
                new MarketplaceListing(
                        savedProduct,
                        Marketplace.YAGA,
                        "other-external",
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
                );
        listing.setShopSlug("nik-ar");
        listing.setProductSlug("5u7arpkm6q");
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);

        return listingRepository.saveAndFlush(listing);
    }

    private long countRows(String entityName) {
        return entityManager
                .createQuery(
                        "select count(entity) from " +
                                entityName +
                                " entity",
                        Long.class
                )
                .getSingleResult();
    }

    private YagaImportedProductData importedData(
            String description,
            BigDecimal price,
            List<String> categoryPath,
            int imageCount
    ) {
        return new YagaImportedProductData(
                500L,
                "nik-ar",
                "5u7arpkm6q",
                description,
                price,
                "EUR",
                "published",
                new YagaImportedProductData.Condition(3L, "Hea"),
                java.util.stream.IntStream
                        .range(0, categoryPath.size())
                        .mapToObj(index ->
                                new YagaImportedProductData.Category(
                                        (long) index + 1,
                                        index == 0 ? null : (long) index,
                                        categoryPath.get(index),
                                        List.of()
                                )
                        )
                        .toList(),
                java.util.stream.IntStream
                        .range(0, imageCount)
                        .mapToObj(index ->
                                new YagaImportedProductData.Image(
                                        "new-image-" + index,
                                        "https://images.yaga.ee/new-" +
                                                index + ".jpg",
                                        "new-" + index + ".jpg"
                                )
                        )
                        .toList(),
                Instant.now(),
                Instant.now(),
                null,
                null
        );
    }

    private static class FakeYagaPageDataClient
            extends YagaPageDataClient {

        private final AtomicReference<YagaImportedProductData> data =
                new AtomicReference<>();
        private final AtomicInteger calls =
                new AtomicInteger();
        private final AtomicReference<RuntimeException> failure =
                new AtomicReference<>();

        private FakeYagaPageDataClient() {
            super(new YagaPageDataParser(
                    new tools.jackson.databind.ObjectMapper()
            ));
        }

        @Override
        public YagaImportedProductData getProduct(String productUrl) {
            calls.incrementAndGet();
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
            return data.get();
        }

        private void setData(YagaImportedProductData data) {
            this.data.set(data);
        }

        private int calls() {
            return calls.get();
        }

        private void reset() {
            calls.set(0);
            failure.set(null);
        }

        private void failWith(RuntimeException exception) {
            failure.set(exception);
        }
    }
}
