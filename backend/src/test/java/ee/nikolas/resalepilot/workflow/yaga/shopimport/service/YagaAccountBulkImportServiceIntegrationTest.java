package ee.nikolas.resalepilot.workflow.yaga.shopimport.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountRepository;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveService;
import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryStopReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaAccountBulkImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportRequestInvalidException;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "yaga.shop-import.enabled=true",
        "logging.level.ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaAccountBulkImportService=warn"
})
@Testcontainers
class YagaAccountBulkImportServiceIntegrationTest {

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
    private YagaAccountBulkImportService service;

    @Autowired
    private YagaShopDiscoveryService discoveryService;

    @Autowired
    private YagaPageDataClient pageDataClient;

    @Autowired
    private YagaImageArchiveService archiveService;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private YagaAccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private final List<Long> archivedAccountIds = new ArrayList<>();
    private final List<String> archivedDriveFolderIds = new ArrayList<>();
    private YagaAccount account2;

    @BeforeEach
    void cleanDatabaseAndMocks() {
        listingRepository.deleteAllInBatch();
        productImageRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        accountRepository.findByShopSlug("w-a-k-a")
                .ifPresent(accountRepository::delete);
        account2 = new YagaAccount(
                "WAKA",
                "w-a-k-a",
                "auth/w-a-k-a.json",
                10
        );
        account2.setDriveFolderId("drive-folder-waka");
        account2 = accountRepository.saveAndFlush(account2);
        entityManager.clear();

        archivedAccountIds.clear();
        archivedDriveFolderIds.clear();
        Mockito.reset(discoveryService, pageDataClient, archiveService);
        doAnswer(invocation -> {
            Long listingId = invocation.getArgument(0);
            jdbcTemplate.queryForObject(
                    """
                    select ya.id, ya.drive_folder_id
                    from marketplace_listings ml
                    join yaga_accounts ya on ya.id = ml.yaga_account_id
                    where ml.id = ?
                    """,
                    (rs, rowNum) -> {
                        archivedAccountIds.add(rs.getLong("id"));
                        archivedDriveFolderIds.add(
                                rs.getString("drive_folder_id")
                        );
                        return null;
                    },
                    listingId
            );
            return new YagaArchiveImagesResponse(
                    listingId,
                    0,
                    0,
                    0,
                    List.of()
            );
        }).when(archiveService).archiveImages(anyLong());
    }

    @Test
    void importsSelectedAccountAndArchivesIntoThatAccountFolder() {
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", discovered(
                        "2001",
                        "w-a-k-a",
                        "first"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/first"))
                .thenReturn(data(2001L, "w-a-k-a", "first", "published"));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId());

        assertThat(response.accountId()).isEqualTo(account2.getId());
        assertThat(response.shopSlug()).isEqualTo("w-a-k-a");
        assertThat(response.discovered()).isEqualTo(1);
        assertThat(response.discoveredTotal()).isEqualTo(1);
        assertThat(response.processedCount()).isEqualTo(1);
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isZero();

        MarketplaceListing listing =
                listingRepository
                        .findByIdWithImages(
                                listingRepository
                                        .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                                                account2.getId(),
                                                Marketplace.YAGA,
                                                "2001"
                                        )
                                        .orElseThrow()
                                        .getId()
                        )
                        .orElseThrow();
        assertThat(listing.getShopSlug()).isEqualTo("w-a-k-a");
        assertThat(listing.getImages())
                .extracting("externalImageId")
                .containsExactly("image-first-a", "image-first-b");
        assertThat(listing.getImages())
                .extracting("displayOrder")
                .containsExactly(0, 1);
        assertThat(archivedAccountIds).containsExactly(account2.getId());
        assertThat(archivedDriveFolderIds)
                .containsExactly("drive-folder-waka");
    }

    @Test
    void sameExternalListingIdInAnotherAccountDoesNotBlockSelectedAccount() {
        YagaAccount account1 =
                accountRepository.findByShopSlug("nik-ar")
                        .orElseThrow();
        existingListing(account1, "nik-ar", "same-external", "3001");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", discovered(
                        "3001",
                        "w-a-k-a",
                        "same-external"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/same-external"))
                .thenReturn(data(
                        3001L,
                        "w-a-k-a",
                        "same-external",
                        "published"
                ));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId());

        assertThat(response.created()).isEqualTo(1);
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account1.getId(),
                        Marketplace.YAGA,
                        "3001"
                )).isPresent();
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        "3001"
                )).isPresent();
        assertThat(listingRepository.count()).isEqualTo(2);
    }

    @Test
    void repeatImportUpdatesExistingListingWithoutDuplicates() {
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", discovered(
                        "4001",
                        "w-a-k-a",
                        "repeat"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/repeat"))
                .thenReturn(
                        data(4001L, "w-a-k-a", "repeat", "published",
                                "First title", BigDecimal.valueOf(10)),
                        data(4001L, "w-a-k-a", "repeat", "published",
                                "Updated title", BigDecimal.valueOf(15))
                );

        YagaAccountBulkImportResponse first =
                service.importCurrentListings(account2.getId());
        YagaAccountBulkImportResponse second =
                service.importCurrentListings(account2.getId());

        assertThat(first.created()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(listingRepository.count()).isEqualTo(1);
        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(productRepository.findAll().getFirst().getTitle())
                .isEqualTo("Updated title");
        assertThat(productRepository.findAll().getFirst().getAskingPrice())
                .isEqualByComparingTo("15");
    }

    @Test
    void importsTwoHundredPlusListingsSequentially() {
        int total = 205;
        List<YagaShopDiscoveredListingResponse> listings =
                new ArrayList<>();
        for (int index = 0; index < total; index++) {
            String slug = "item-" + index;
            String externalId = String.valueOf(5000 + index);
            listings.add(discovered(externalId, "w-a-k-a", slug));
            Mockito.lenient().when(pageDataClient.getProduct(
                    "https://www.yaga.ee/w-a-k-a/toode/" + slug))
                    .thenReturn(data(
                            5000L + index,
                            "w-a-k-a",
                            slug,
                            "published"
                    ));
        }
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", listings));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId());

        assertThat(response.discovered()).isEqualTo(total);
        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(total);
        assertThat(response.created()).isEqualTo(total);
        assertThat(response.failed()).isZero();
        assertThat(listingRepository.count()).isEqualTo(total);
        assertThat(archivedAccountIds).hasSize(total);
        Mockito.verify(pageDataClient, Mockito.times(total))
                .getProduct(Mockito.anyString());
    }

    @Test
    void noLimitImportsAllDiscoveredListings() {
        int total = 7;
        stubDiscoveredListings(total, 9000);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), null);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(total);
        assertThat(response.created()).isEqualTo(total);
        assertThat(listingRepository.count()).isEqualTo(total);
        Mockito.verify(pageDataClient, Mockito.times(total))
                .getProduct(Mockito.anyString());
        Mockito.verify(discoveryService).discover("w-a-k-a");
        Mockito.verify(discoveryService, Mockito.never())
                .discover(Mockito.eq("w-a-k-a"), Mockito.any());
    }

    @Test
    void limitFiveProcessesAtMostFiveListings() {
        int total = 8;
        stubDiscoveredListings(total, 9100);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(5);
        assertThat(response.created()).isEqualTo(5);
        assertThat(listingRepository.count()).isEqualTo(5);
        Mockito.verify(pageDataClient, Mockito.times(5))
                .getProduct(Mockito.anyString());
        Mockito.verify(discoveryService).discover("w-a-k-a", 5);
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        "9105"
                )).isEmpty();
    }

    @Test
    void offsetZeroLimitFiveProcessesFirstFiveListings() {
        int total = 10;
        stubDiscoveredListings(total, 9400);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5, 0);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(5);
        assertThat(response.created()).isEqualTo(5);
        assertListingExists("9400");
        assertListingExists("9404");
        assertListingMissing("9405");
        Mockito.verify(discoveryService).discover("w-a-k-a", 5);
    }

    @Test
    void offsetFiveLimitFiveProcessesNextFiveListings() {
        int total = 12;
        stubDiscoveredListings(total, 9500);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5, 5);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(5);
        assertThat(response.created()).isEqualTo(5);
        assertThat(listingRepository.count()).isEqualTo(5);
        assertListingMissing("9500");
        assertListingExists("9505");
        assertListingExists("9509");
        assertListingMissing("9510");
        Mockito.verify(discoveryService).discover("w-a-k-a", 10);
    }

    @Test
    void offsetPastEndProcessesNoListings() {
        int total = 3;
        stubDiscoveredListings(total, 9600);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5, 5);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isZero();
        assertThat(response.created()).isZero();
        assertThat(response.updated()).isZero();
        assertThat(response.failed()).isZero();
        assertThat(listingRepository.count()).isZero();
        Mockito.verifyNoInteractions(pageDataClient);
        Mockito.verify(discoveryService).discover("w-a-k-a", 10);
    }

    @Test
    void limitOneProcessesOneListing() {
        int total = 4;
        stubDiscoveredListings(total, 9200);

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 1);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(1);
        assertThat(response.created()).isEqualTo(1);
        assertThat(listingRepository.count()).isEqualTo(1);
        Mockito.verify(pageDataClient, Mockito.times(1))
                .getProduct(Mockito.anyString());
        Mockito.verify(discoveryService).discover("w-a-k-a", 1);
    }

    @Test
    void invalidLimitZeroOrNegativeIsRejected() {
        assertThatThrownBy(() ->
                service.importCurrentListings(account2.getId(), 0))
                .isInstanceOf(YagaShopImportRequestInvalidException.class)
                .hasMessage("limit must be greater than 0");
        assertThatThrownBy(() ->
                service.importCurrentListings(account2.getId(), -1))
                .isInstanceOf(YagaShopImportRequestInvalidException.class)
                .hasMessage("limit must be greater than 0");

        Mockito.verifyNoInteractions(discoveryService);
        Mockito.verifyNoInteractions(pageDataClient);
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void invalidNegativeOffsetIsRejected() {
        assertThatThrownBy(() ->
                service.importCurrentListings(account2.getId(), 5, -1))
                .isInstanceOf(YagaShopImportRequestInvalidException.class)
                .hasMessage("offset must be greater than or equal to 0");

        Mockito.verifyNoInteractions(discoveryService);
        Mockito.verifyNoInteractions(pageDataClient);
        assertThat(listingRepository.count()).isZero();
    }

    @Test
    void repeatLimitedImportUpdatesWithoutDuplicates() {
        int total = 6;
        stubDiscoveredListings(total, 9300);

        YagaAccountBulkImportResponse first =
                service.importCurrentListings(account2.getId(), 5);
        YagaAccountBulkImportResponse second =
                service.importCurrentListings(account2.getId(), 5);

        assertThat(first.created()).isEqualTo(5);
        assertThat(second.updated()).isEqualTo(5);
        assertThat(first.processedCount()).isEqualTo(5);
        assertThat(second.processedCount()).isEqualTo(5);
        assertThat(listingRepository.count()).isEqualTo(5);
        assertThat(productRepository.count()).isEqualTo(5);
        Mockito.verify(pageDataClient, Mockito.times(10))
                .getProduct(Mockito.anyString());
    }

    @Test
    void repeatSameOffsetUpdatesWithoutDuplicates() {
        int total = 12;
        stubDiscoveredListings(total, 9700);

        YagaAccountBulkImportResponse first =
                service.importCurrentListings(account2.getId(), 5, 5);
        YagaAccountBulkImportResponse second =
                service.importCurrentListings(account2.getId(), 5, 5);

        assertThat(first.created()).isEqualTo(5);
        assertThat(second.updated()).isEqualTo(5);
        assertThat(first.processedCount()).isEqualTo(5);
        assertThat(second.processedCount()).isEqualTo(5);
        assertThat(listingRepository.count()).isEqualTo(5);
        assertListingMissing("9704");
        assertListingExists("9705");
        assertListingExists("9709");
        assertListingMissing("9710");
        Mockito.verify(pageDataClient, Mockito.times(10))
                .getProduct(Mockito.anyString());
    }


    @Test
    void onlyNewSkipsExistingNineAndProcessesFiveNewListings() {
        int total = 14;
        stubPageData(total, 9800);
        List<YagaShopDiscoveredListingResponse> newListings =
                discoveredListings(9809, 5);
        when(discoveryService.discover("w-a-k-a", 5, true))
                .thenReturn(discovery("w-a-k-a", total, newListings));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5, null, true);

        assertThat(response.discoveredTotal()).isEqualTo(total);
        assertThat(response.processedCount()).isEqualTo(5);
        assertThat(response.created()).isEqualTo(5);
        assertListingMissing("9808");
        assertListingExists("9809");
        assertListingExists("9813");
        Mockito.verify(pageDataClient, Mockito.times(5))
                .getProduct(Mockito.anyString());
    }

    @Test
    void repeatOnlyNewImportProcessesNextNewListings() {
        int total = 19;
        stubPageData(total, 9900);
        when(discoveryService.discover("w-a-k-a", 5, true))
                .thenReturn(
                        discovery(
                                "w-a-k-a",
                                total,
                                discoveredListings(9909, 5)
                        ),
                        discovery(
                                "w-a-k-a",
                                total,
                                discoveredListings(9914, 5)
                        )
                );

        YagaAccountBulkImportResponse first =
                service.importCurrentListings(account2.getId(), 5, null, true);
        YagaAccountBulkImportResponse second =
                service.importCurrentListings(account2.getId(), 5, null, true);

        assertThat(first.created()).isEqualTo(5);
        assertThat(second.created()).isEqualTo(5);
        assertThat(listingRepository.count()).isEqualTo(10);
        assertListingMissing("9908");
        assertListingExists("9909");
        assertListingExists("9913");
        assertListingExists("9914");
        assertListingExists("9918");
        Mockito.verify(pageDataClient, Mockito.times(10))
                .getProduct(Mockito.anyString());
    }

    @Test
    void onlyNewAccountIsolationDoesNotSkipAnotherAccountListing() {
        YagaAccount account1 =
                accountRepository.findByShopSlug("nik-ar")
                        .orElseThrow();
        existingListing(account1, "nik-ar", "shared", "10001");
        when(discoveryService.discover("w-a-k-a", 5, true))
                .thenReturn(discovery(
                        "w-a-k-a",
                        1,
                        discovered("10001", "w-a-k-a", "shared")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/shared"))
                .thenReturn(data(10001L, "w-a-k-a", "shared", "published"));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId(), 5, null, true);

        assertThat(response.created()).isEqualTo(1);
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account1.getId(),
                        Marketplace.YAGA,
                        "10001"
                )).isPresent();
        assertListingExists("10001");
    }


    @Test
    void oneFailedListingDoesNotRollbackSuccessfulImports() {
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        discovered("6001", "w-a-k-a", "bad"),
                        discovered("6002", "w-a-k-a", "good")
                ));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/bad"))
                .thenThrow(new IllegalStateException("detail failed"));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/good"))
                .thenReturn(data(6002L, "w-a-k-a", "good", "published"));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId());

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.failures())
                .singleElement()
                .extracting("externalListingId")
                .isEqualTo("6001");
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        "6002"
                )).isPresent();
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        "6001"
                )).isEmpty();
    }

    @Test
    void wrongFetchedShopIsRejectedWithoutCreatingListing() {
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", discovered(
                        "7001",
                        "w-a-k-a",
                        "wrong-shop"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/w-a-k-a/toode/wrong-shop"))
                .thenReturn(data(
                        7001L,
                        "nik-ar",
                        "wrong-shop",
                        "published"
                ));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account2.getId());

        assertThat(response.created()).isZero();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(listingRepository.count()).isZero();
        assertThat(archivedAccountIds).isEmpty();
    }

    @Test
    void existingAccountOneImportBehaviorRemainsCompatible() {
        YagaAccount account1 =
                accountRepository.findByShopSlug("nik-ar")
                        .orElseThrow();
        when(discoveryService.discover("nik-ar"))
                .thenReturn(discovery("nik-ar", discovered(
                        "8001",
                        "nik-ar",
                        "legacy"
                )));
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/legacy"))
                .thenReturn(data(8001L, "nik-ar", "legacy", "published"));

        YagaAccountBulkImportResponse response =
                service.importCurrentListings(account1.getId());

        assertThat(response.created()).isEqualTo(1);
        assertThat(productRepository.findBySku("YAGA-8001")).isPresent();
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account1.getId(),
                        Marketplace.YAGA,
                        "8001"
                )).isPresent();
    }

    private MarketplaceListing existingListing(
            YagaAccount account,
            String shopSlug,
            String productSlug,
            String externalListingId
    ) {
        Product product = productRepository.saveAndFlush(
                new Product(
                        "EXISTING-" + shopSlug + "-" + externalListingId,
                        "Existing"
                )
        );
        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                externalListingId,
                "https://www.yaga.ee/" + shopSlug + "/toode/" + productSlug
        );
        listing.setYagaAccount(account);
        listing.setShopSlug(shopSlug);
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(true);
        return listingRepository.saveAndFlush(listing);
    }

    private YagaShopDiscoveryResponse discovery(
            String shopSlug,
            YagaShopDiscoveredListingResponse... listings
    ) {
        return discovery(shopSlug, List.of(listings));
    }

    private YagaShopDiscoveryResponse discovery(
            String shopSlug,
            List<YagaShopDiscoveredListingResponse> listings
    ) {
        return discovery(shopSlug, listings.size(), listings);
    }

    private YagaShopDiscoveryResponse discovery(
            String shopSlug,
            int discoveredTotal,
            YagaShopDiscoveredListingResponse... listings
    ) {
        return discovery(shopSlug, discoveredTotal, List.of(listings));
    }

    private YagaShopDiscoveryResponse discovery(
            String shopSlug,
            int discoveredTotal,
            List<YagaShopDiscoveredListingResponse> listings
    ) {
        return new YagaShopDiscoveryResponse(
                shopSlug,
                "PUBLIC_API",
                Math.max(1, (discoveredTotal + 39) / 40),
                discoveredTotal,
                discoveredTotal,
                listings.size(),
                listings.size(),
                0,
                0,
                0,
                false,
                true,
                YagaShopDiscoveryStopReason.CONFIRMED_END,
                discoveredTotal,
                discoveredTotal,
                "$.products.total",
                true,
                List.of(discoveredTotal),
                discoveredTotal,
                discoveredTotal,
                discoveredTotal,
                false,
                discoveredTotal,
                List.of(),
                List.of(),
                List.of(),
                List.of("$.products.total=" + discoveredTotal),
                false,
                "$.products.pageInfo.hasNextPage",
                false,
                null,
                null,
                "$.products.items",
                List.of(),
                false,
                List.of(),
                List.of(discoveredTotal),
                listings,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private YagaShopDiscoveredListingResponse discovered(
            String externalId,
            String shopSlug,
            String productSlug
    ) {
        return new YagaShopDiscoveredListingResponse(
                externalId,
                productSlug,
                "Yaga title " + productSlug,
                "https://www.yaga.ee/" + shopSlug + "/toode/" + productSlug,
                Instant.parse("2026-01-01T00:00:00Z"),
                2
        );
    }

    private void stubDiscoveredListings(int total, int firstExternalId) {
        List<YagaShopDiscoveredListingResponse> listings =
                new ArrayList<>();
        for (int index = 0; index < total; index++) {
            String slug = "limited-item-" + firstExternalId + "-" + index;
            String externalId = String.valueOf(firstExternalId + index);
            listings.add(discovered(externalId, "w-a-k-a", slug));
            when(pageDataClient.getProduct(
                    "https://www.yaga.ee/w-a-k-a/toode/" + slug))
                    .thenReturn(data(
                            firstExternalId + index,
                            "w-a-k-a",
                            slug,
                            "published"
                    ));
        }
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", listings));
        when(discoveryService.discover(Mockito.eq("w-a-k-a"), Mockito.any()))
                .thenReturn(discovery("w-a-k-a", listings));
    }

    private List<YagaShopDiscoveredListingResponse> discoveredListings(
            int firstExternalId,
            int count
    ) {
        List<YagaShopDiscoveredListingResponse> listings =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int externalId = firstExternalId + index;
            String slug = "limited-item-" + externalId;
            listings.add(discovered(
                    String.valueOf(externalId),
                    "w-a-k-a",
                    slug
            ));
        }
        return listings;
    }

    private void stubPageData(int total, int firstExternalId) {
        for (int index = 0; index < total; index++) {
            int externalId = firstExternalId + index;
            String slug = "limited-item-" + externalId;
            when(pageDataClient.getProduct(
                    "https://www.yaga.ee/w-a-k-a/toode/" + slug))
                    .thenReturn(data(
                            externalId,
                            "w-a-k-a",
                            slug,
                            "published"
                    ));
        }
    }

    private void assertListingExists(String externalId) {
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        externalId
                )).isPresent();
    }

    private void assertListingMissing(String externalId) {
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account2.getId(),
                        Marketplace.YAGA,
                        externalId
                )).isEmpty();
    }

    private YagaImportedProductData data(
            long externalId,
            String shopSlug,
            String productSlug,
            String status
    ) {
        return data(
                externalId,
                shopSlug,
                productSlug,
                status,
                "Yaga title " + productSlug,
                BigDecimal.valueOf(17)
        );
    }

    private YagaImportedProductData data(
            long externalId,
            String shopSlug,
            String productSlug,
            String status,
            String title,
            BigDecimal price
    ) {
        return new YagaImportedProductData(
                externalId,
                shopSlug,
                productSlug,
                title,
                "Description " + productSlug,
                price,
                "EUR",
                status,
                new YagaImportedProductData.Condition(3L, "Hea"),
                List.of(
                        new YagaImportedProductData.Category(
                                1L,
                                null,
                                "Books",
                                List.of()
                        ),
                        new YagaImportedProductData.Category(
                                2L,
                                1L,
                                "History",
                                List.of()
                        )
                ),
                List.of(
                        new YagaImportedProductData.Image(
                                "image-" + productSlug + "-a",
                                "https://images.yaga.ee/" + productSlug + "-a.jpg",
                                productSlug + "-a.jpg"
                        ),
                        new YagaImportedProductData.Image(
                                "image-" + productSlug + "-b",
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

        @Bean
        @Primary
        YagaImageArchiveService archiveService() {
            return Mockito.mock(YagaImageArchiveService.class);
        }
    }
}
