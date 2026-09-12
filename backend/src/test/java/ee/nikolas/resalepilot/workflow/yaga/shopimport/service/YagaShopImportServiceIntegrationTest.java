package ee.nikolas.resalepilot.workflow.yaga.shopimport.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.parser.YagaPageDataParser;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryStopReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItemStatus;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRun;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportItemRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportRunRepository;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "yaga.shop-import.enabled=true",
        "yaga.shop-import.default-max-items=2",
        "yaga.shop-import.max-items=5"
})
@Testcontainers
class YagaShopImportServiceIntegrationTest {

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
    private YagaShopImportService service;

    @Autowired
    private YagaShopDiscoveryService discoveryService;

    @Autowired
    private YagaPageDataClient pageDataClient;

    @Autowired
    private YagaShopImportRunRepository runRepository;

    @Autowired
    private YagaShopImportItemRepository itemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabaseAndMocks() {
        itemRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        productImageRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        Mockito.reset(discoveryService, pageDataClient);
    }

    @Test
    void preparationSelectsOnlyActiveNewAndDoesNotCreateProductOrListing() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("a", "b", "c"));

        YagaShopImportRunResponse response =
                service.prepare("nik-ar", 2, "prepare-only");

        assertThat(response.status())
                .isEqualTo(YagaShopImportRunStatus.AWAITING_CONFIRMATION);
        assertThat(response.requestedMaxItems()).isEqualTo(2);
        assertThat(response.selectedItemCount()).isEqualTo(2);
        assertThat(response.items())
                .extracting("productSlug")
                .containsExactly("a", "b");
        assertThat(response.items())
                .extracting("title")
                .containsExactly("Yaga title a", "Yaga title b");
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void defaultMaxItemsAndSameIdempotencyKeyReturnSameSnapshot() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("a", "b", "c"));

        YagaShopImportRunResponse first =
                service.prepare("nik-ar", null, "same-key");
        YagaShopImportRunResponse second =
                service.prepare("nik-ar", 5, "same-key");

        assertThat(first.requestedMaxItems()).isEqualTo(2);
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.items())
                .extracting("productSlug")
                .containsExactly("a", "b");
        entityManager.clear();
        assertThat(service.get(first.runId()).items())
                .extracting("title")
                .containsExactly("Yaga title a", "Yaga title b");
        assertThat(runRepository.count()).isEqualTo(1);
        assertThat(itemRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidMaxItemsAndPhraseAreRejected() {
        assertThatThrownBy(() -> service.prepare("nik-ar", 0, "bad-zero"))
                .isInstanceOf(YagaShopImportRequestInvalidException.class);
        assertThatThrownBy(() -> service.prepare("nik-ar", 6, "bad-large"))
                .isInstanceOf(YagaShopImportRequestInvalidException.class);

        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("a"));
        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 1, "bad-phrase");

        assertThatThrownBy(() ->
                service.confirm(run.runId(), "YES"))
                .isInstanceOf(YagaShopImportRequestInvalidException.class);
    }

    @Test
    void confirmImportsActivePublishedListingWithStableSkuAndNoProductImage() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(discovered("101", "book")));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/book"))
                .thenReturn(data(101L, "book", "published"));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "import-one");
        YagaShopImportRunResponse confirmed =
                service.confirm(prepared.runId(), "IMPORT");

        assertThat(confirmed.status())
                .isEqualTo(YagaShopImportRunStatus.COMPLETED);
        assertThat(confirmed.importedCount()).isEqualTo(1);
        assertThat(confirmed.items().getFirst().status())
                .isEqualTo(YagaShopImportItemStatus.IMPORTED);
        assertThat(productRepository.findBySku("YAGA-101"))
                .get()
                .extracting(Product::getTitle)
                .isEqualTo("Yaga title book");
        MarketplaceListing listing =
                listingRepository.findByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        "101"
                ).orElseThrow();
        assertThat(jdbcTemplate.queryForList(
                "select title from marketplace_listing_categories " +
                        "where marketplace_listing_id = ? " +
                        "order by category_level",
                String.class,
                listing.getId()
        )).containsExactly("Raamatud", "Ajalugu");
        assertThat(jdbcTemplate.queryForList(
                "select display_order from marketplace_listing_images " +
                        "where marketplace_listing_id = ? " +
                        "order by display_order",
                Integer.class,
                listing.getId()
        )).containsExactly(0, 1);
        assertThat(productImageRepository.count()).isZero();
    }

    @Test
    void missingStructuredTitleUsesDescriptionFirstLineAndKeepsFullDescription() {
        String fullDescription = """
                First title line

                Second line remains in full description.
                """;
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(discovered(
                        "150",
                        "description-title",
                        "First title line"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/description-title"))
                .thenReturn(data(
                        150L,
                        "description-title",
                        "published",
                        null,
                        fullDescription
                ));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "description-title");
        YagaShopImportRunResponse confirmed =
                service.confirm(prepared.runId(), "IMPORT");

        assertThat(prepared.items().getFirst().title())
                .isEqualTo("First title line");
        assertThat(confirmed.importedCount()).isEqualTo(1);
        Product product = productRepository.findBySku("YAGA-150")
                .orElseThrow();
        assertThat(product.getTitle()).isEqualTo("First title line");
        assertThat(product.getDescription()).isEqualTo(fullDescription);
    }

    @Test
    void inactiveOrUnknownBeforeConfirmAreSkippedWithoutCreatingEntities() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("sold", "hidden", "unknown"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/sold"))
                .thenReturn(data(201L, "sold", "sold"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/hidden"))
                .thenReturn(hiddenData(202L, "hidden"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/unknown"))
                .thenReturn(data(203L, "unknown", "mystery"));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 3, "skip-inactive");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.skippedCount()).isEqualTo(3);
        assertThat(confirmed.items())
                .extracting("status")
                .containsOnly(YagaShopImportItemStatus.SKIPPED_NOT_ACTIVE);
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void invalidTitleBeforeConfirmIsSkippedWithoutCreatingEntities() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("211", "blank", "Prepared title"),
                        discovered("212", "missing", "Prepared title 2")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/blank"))
                .thenReturn(data(
                        211L,
                        "blank",
                        "published",
                        "   ",
                        "   "
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/missing"))
                .thenReturn(data(
                        212L,
                        "missing",
                        "published",
                        null,
                        null
                ));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 2, "invalid-title");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.skippedCount()).isEqualTo(2);
        assertThat(confirmed.items())
                .extracting("status")
                .containsOnly(YagaShopImportItemStatus.SKIPPED_INVALID_DATA);
        assertThat(confirmed.items())
                .extracting("lastErrorCode")
                .containsOnly("INVALID_TITLE");
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void invalidTitleDoesNotConsumePrepareMaxItems() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("214", "blank", null),
                        discovered("215", "valid", "Resolved title")
                ));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "prepare-invalid-title");

        assertThat(prepared.selectedItemCount()).isEqualTo(1);
        assertThat(prepared.items()).singleElement().satisfies(item -> {
            assertThat(item.productSlug()).isEqualTo("valid");
            assertThat(item.title()).isEqualTo("Resolved title");
            assertThat(item.status())
                    .isEqualTo(YagaShopImportItemStatus.SELECTED);
        });
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void importedProductTitleIsTrimmed() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(discovered(
                        "220",
                        "trimmed",
                        "  Real Yaga title  "
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/trimmed"))
                .thenReturn(data(
                        220L,
                        "trimmed",
                        "published",
                        "  Real Yaga title  "
                ));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 1, "trim-title");
        service.confirm(run.runId(), "IMPORT");

        assertThat(productRepository.findBySku("YAGA-220"))
                .get()
                .extracting(Product::getTitle)
                .isEqualTo("Real Yaga title");
    }

    @Test
    void titleChangeAfterPrepareSkipsItemWithoutImporting() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("221", "changed", "Original title")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/changed"))
                .thenReturn(data(
                        221L,
                        "changed",
                        "published",
                        null,
                        "Changed description first line"
                ));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 1, "title-changed");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.skippedCount()).isEqualTo(1);
        assertThat(confirmed.items().getFirst().status())
                .isEqualTo(YagaShopImportItemStatus.SKIPPED_INVALID_DATA);
        assertThat(confirmed.items().getFirst().lastErrorCode())
                .isEqualTo("TITLE_SNAPSHOT_MISMATCH");
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void legacyItemWithoutSelectedTitleDoesNotImport() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(discovered("222", "legacy")));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/legacy"))
                .thenReturn(data(222L, "legacy", "published"));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "legacy-title");
        YagaShopImportRun run = runRepository.findWithItemsById(prepared.runId())
                .orElseThrow();
        run.getItems().getFirst().setSelectedTitle(null);
        runRepository.saveAndFlush(run);
        entityManager.clear();

        YagaShopImportRunResponse confirmed =
                service.confirm(prepared.runId(), "IMPORT");

        assertThat(confirmed.items().getFirst().status())
                .isEqualTo(YagaShopImportItemStatus.SKIPPED_INVALID_DATA);
        assertThat(confirmed.items().getFirst().lastErrorCode())
                .isEqualTo("INVALID_SELECTED_TITLE");
        assertThat(productRepository.count()).isZero();
        assertThat(listingRepository.count()).isZero();
        Mockito.verify(pageDataClient, Mockito.never())
                .getProduct(anyString());
    }

    @Test
    void existingByExternalIdOrSlugBecomesAlreadyExists() {
        MarketplaceListing byExternal = existingListing("existing-ext", "301");
        MarketplaceListing bySlug = existingListing("existing-slug", "999");
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("301", "different-slug"),
                        discovered("302", "existing-slug")
                ));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 2, "existing");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.existingCount()).isEqualTo(2);
        assertThat(confirmed.items())
                .extracting("status")
                .containsOnly(YagaShopImportItemStatus.ALREADY_EXISTS);
        assertThat(confirmed.items())
                .extracting("marketplaceListingId")
                .containsExactlyInAnyOrder(byExternal.getId(), bySlug.getId());
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(listingRepository.count()).isEqualTo(2);
        Mockito.verify(pageDataClient, Mockito.never())
                .getProduct(anyString());
    }

    @Test
    void secondConfirmDoesNotCreateDuplicates() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(discovered("401", "book")));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/book"))
                .thenReturn(data(401L, "book", "published"));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 1, "second-confirm");
        service.confirm(run.runId(), "IMPORT");
        YagaShopImportRunResponse second =
                service.confirm(run.runId(), "IMPORT");

        assertThat(second.importedCount()).isEqualTo(1);
        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(listingRepository.count()).isEqualTo(1);
    }

    @Test
    void partialFailureDoesNotRollbackSuccessfulItems() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("500", "bad"),
                        discovered("501", "good")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/bad"))
                .thenThrow(new IllegalStateException("detail failed"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/good"))
                .thenReturn(data(501L, "good", "published"));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 2, "partial");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.status())
                .isEqualTo(YagaShopImportRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(confirmed.importedCount()).isEqualTo(1);
        assertThat(confirmed.failedCount()).isEqualTo(1);
        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(listingRepository.count()).isEqualTo(1);
    }

    @Test
    void itemPersistenceFailureRollsBackOnlyThatItem() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery(
                        discovered("510", "good"),
                        discovered("511", "bad")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/good"))
                .thenReturn(data(510L, "good", "published"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/bad"))
                .thenReturn(dataWithNullImageUrl(511L, "bad"));

        YagaShopImportRunResponse run =
                service.prepare("nik-ar", 2, "partial-db-failure");
        YagaShopImportRunResponse confirmed =
                service.confirm(run.runId(), "IMPORT");

        assertThat(confirmed.importedCount()).isEqualTo(1);
        assertThat(confirmed.failedCount()).isEqualTo(1);
        assertThat(productRepository.findBySku("YAGA-510")).isPresent();
        assertThat(productRepository.findBySku("YAGA-511")).isEmpty();
        assertThat(listingRepository
                .findByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        "510"
                )).isPresent();
        assertThat(listingRepository
                .findByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        "511"
                )).isEmpty();
    }

    @Test
    void recoveryAfterImportingStateMarksExistingAndContinues() {
        MarketplaceListing existing = existingListing("recovered", "601");
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("next"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/next"))
                .thenReturn(data(602L, "next", "published"));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "recovery");
        YagaShopImportRun run = runRepository.findWithItemsById(prepared.runId())
                .orElseThrow();
        run.setStatus(YagaShopImportRunStatus.IMPORTING);
        run.getItems().getFirst().setExternalListingId("601");
        run.getItems().getFirst().setProductSlug("recovered");
        run.getItems().getFirst().setPublicUrl(
                "https://www.yaga.ee/nik-ar/toode/recovered"
        );
        runRepository.saveAndFlush(run);
        entityManager.clear();

        YagaShopImportRunResponse confirmed =
                service.confirm(prepared.runId(), "IMPORT");

        assertThat(confirmed.existingCount()).isEqualTo(1);
        assertThat(confirmed.items().getFirst().marketplaceListingId())
                .isEqualTo(existing.getId());
    }

    @Test
    void getWorksAfterClearingPersistenceContextAndCancelBeforeImport() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("a"));

        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "get-cancel");
        entityManager.clear();

        assertThat(service.get(prepared.runId()).items()).hasSize(1);
        YagaShopImportRunResponse cancelled =
                service.cancel(prepared.runId());
        assertThat(cancelled.status())
                .isEqualTo(YagaShopImportRunStatus.CANCELLED);
    }

    @Test
    void cancelAfterImportStartedIsRejected() {
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("a"));
        YagaShopImportRunResponse prepared =
                service.prepare("nik-ar", 1, "cancel-reject");
        YagaShopImportRun run = runRepository.findWithItemsById(prepared.runId())
                .orElseThrow();
        run.setStatus(YagaShopImportRunStatus.IMPORTING);
        runRepository.saveAndFlush(run);

        assertThatThrownBy(() -> service.cancel(prepared.runId()))
                .isInstanceOf(YagaShopImportInvalidStateException.class);
    }

    @Test
    void workflowDoesNotImportDriveOrPlaywright() throws Exception {
        Path root = Path.of(
                "src/main/java/ee/nikolas/resalepilot/workflow/yaga/shopimport"
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
                        content.contains("playwright") ||
                                content.contains("Playwright") ||
                                content.contains("GoogleDrive") ||
                                content.contains("DriveArchive"));
    }

    private MarketplaceListing existingListing(
            String productSlug,
            String externalListingId
    ) {
        Product product = productRepository.saveAndFlush(
                new Product("EXISTING-" + externalListingId, "Existing")
        );
        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                externalListingId,
                "https://www.yaga.ee/nik-ar/toode/" + productSlug
        );
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(true);
        return listingRepository.saveAndFlush(listing);
    }

    private YagaShopDiscoveryResponse discovery(String... productSlugs) {
        return discovery(
                List.of(productSlugs)
                        .stream()
                        .map(slug -> discovered(
                                String.valueOf(Math.abs(slug.hashCode())),
                                slug
                        ))
                        .toList()
        );
    }

    private YagaShopDiscoveryResponse discovery(
            YagaShopDiscoveredListingResponse... listings
    ) {
        return discovery(List.of(listings));
    }

    private YagaShopDiscoveryResponse discovery(
            List<YagaShopDiscoveredListingResponse> listings
    ) {
        return new YagaShopDiscoveryResponse(
                "nik-ar",
                "PUBLIC_API",
                1,
                listings.size(),
                listings.size(),
                listings.size(),
                listings.size(),
                0,
                0,
                0,
                false,
                true,
                YagaShopDiscoveryStopReason.CONFIRMED_END,
                listings.size(),
                listings.size(),
                "$.products.total",
                true,
                List.of(listings.size()),
                listings.size(),
                listings.size(),
                listings.size(),
                false,
                listings.size(),
                List.of(),
                List.of(),
                List.of(),
                List.of("$.products.total=" + listings.size()),
                false,
                "$.products.pageInfo.hasNextPage",
                false,
                null,
                null,
                "$.products.items",
                List.of(),
                false,
                List.of(),
                List.of(listings.size()),
                listings,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private YagaShopDiscoveredListingResponse discovered(
            String externalId,
            String productSlug
    ) {
        return discovered(
                externalId,
                productSlug,
                "Yaga title " + productSlug
        );
    }

    private YagaShopDiscoveredListingResponse discovered(
            String externalId,
            String productSlug,
            String title
    ) {
        return new YagaShopDiscoveredListingResponse(
                externalId,
                productSlug,
                title,
                "https://www.yaga.ee/nik-ar/toode/" + productSlug,
                Instant.parse("2026-01-01T00:00:00Z"),
                2
        );
    }

    private YagaImportedProductData data(
            long externalId,
            String productSlug,
            String status
    ) {
        return data(
                externalId,
                productSlug,
                status,
                "Yaga title " + productSlug
        );
    }

    private YagaImportedProductData data(
            long externalId,
            String productSlug,
            String status,
            String title
    ) {
        return data(
                externalId,
                productSlug,
                status,
                title,
                "Description omitted from API responses"
        );
    }

    private YagaImportedProductData data(
            long externalId,
            String productSlug,
            String status,
            String title,
            String description
    ) {
        return new YagaImportedProductData(
                externalId,
                "nik-ar",
                productSlug,
                title,
                description,
                BigDecimal.valueOf(17),
                "EUR",
                status,
                new YagaImportedProductData.Condition(3L, "Hea"),
                List.of(
                        new YagaImportedProductData.Category(
                                1L,
                                null,
                                "Raamatud",
                                List.of()
                        ),
                        new YagaImportedProductData.Category(
                                2L,
                                1L,
                                "Ajalugu",
                                List.of()
                        )
                ),
                List.of(
                        new YagaImportedProductData.Image(
                                "image-a-" + productSlug,
                                "https://images.yaga.ee/" + productSlug + "-a.jpg",
                                productSlug + "-a.jpg"
                        ),
                        new YagaImportedProductData.Image(
                                "image-b-" + productSlug,
                                "https://images.yaga.ee/" + productSlug + "-b.jpg",
                                productSlug + "-b.jpg"
                        )
                ),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null
        );
    }

    private YagaImportedProductData dataWithNullImageUrl(
            long externalId,
            String productSlug
    ) {
        YagaImportedProductData base =
                data(externalId, productSlug, "published");
        return new YagaImportedProductData(
                base.externalId(),
                base.shopSlug(),
                base.productSlug(),
                base.title(),
                base.description(),
                base.price(),
                base.currency(),
                base.status(),
                base.condition(),
                base.categoryPath(),
                List.of(new YagaImportedProductData.Image(
                        "image-bad",
                        null,
                        "bad.jpg"
                )),
                base.createdAt(),
                base.updatedAt(),
                base.hiddenAt(),
                base.deletedAt()
        );
    }

    private YagaImportedProductData hiddenData(
            long externalId,
            String productSlug
    ) {
        YagaImportedProductData base =
                data(externalId, productSlug, "published");
        return new YagaImportedProductData(
                base.externalId(),
                base.shopSlug(),
                base.productSlug(),
                base.title(),
                base.description(),
                base.price(),
                base.currency(),
                base.status(),
                base.condition(),
                base.categoryPath(),
                base.images(),
                base.createdAt(),
                base.updatedAt(),
                Instant.parse("2026-01-02T00:00:00Z"),
                null
        );
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        YagaShopDiscoveryService discoveryService() {
            return Mockito.mock(YagaShopDiscoveryService.class);
        }

        @Bean
        @Primary
        YagaPageDataClient pageDataClient() {
            return Mockito.mock(YagaPageDataClient.class);
        }
    }
}
