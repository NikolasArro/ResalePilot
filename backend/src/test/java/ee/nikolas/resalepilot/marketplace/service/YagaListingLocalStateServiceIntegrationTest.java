package ee.nikolas.resalepilot.marketplace.service;

import ee.nikolas.resalepilot.marketplace.dto.MarketplaceListingStatusResponse;
import ee.nikolas.resalepilot.marketplace.dto.YagaListingReconciliationResponse;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryStopReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshCandidateRow;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class YagaListingLocalStateServiceIntegrationTest {

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
    private YagaListingLocalStateService service;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private YagaAccountRepository accountRepository;

    @Autowired
    private YagaRefreshJobRepository refreshJobRepository;

    @MockitoBean
    private YagaShopDiscoveryService discoveryService;

    private YagaAccount account1;
    private YagaAccount account2;

    @BeforeEach
    void setUp() {
        listingRepository.deleteAllInBatch();
        productImageRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();

        account1 = accountRepository.findByShopSlug("nik-ar").orElseThrow();
        account2 = accountRepository.findByShopSlug("w-a-k-a")
                .orElseGet(() -> accountRepository.saveAndFlush(
                        new YagaAccount(
                                "Second shop",
                                "w-a-k-a",
                                "../playwright/.auth/yaga-state-2.json",
                                10
                        )
                ));
    }

    @Test
    void soldByAccountAndProductSlugSetsNonCurrent() {
        MarketplaceListing listing = listing(
                account2,
                "2001",
                "shared-slug"
        );

        MarketplaceListingStatusResponse response =
                service.updateStatusBySlug(
                        account2.getId(),
                        "shared-slug",
                        MarketplaceListingStatus.SOLD
                );

        MarketplaceListing persisted = listingRepository
                .findById(listing.getId()).orElseThrow();
        assertThat(response.status())
                .isEqualTo(MarketplaceListingStatus.SOLD);
        assertThat(response.current()).isFalse();
        assertThat(persisted.getStatus())
                .isEqualTo(MarketplaceListingStatus.SOLD);
        assertThat(persisted.isCurrent()).isFalse();
    }

    @Test
    void wrongAccountRejected() {
        listing(account2, "2002", "account-2-item");

        assertThatThrownBy(() -> service.updateStatusBySlug(
                account1.getId(),
                "account-2-item",
                MarketplaceListingStatus.SOLD
        )).isInstanceOf(MarketplaceListingNotFoundException.class);
    }

    @Test
    void unknownSlugRejected() {
        assertThatThrownBy(() -> service.updateStatusBySlug(
                account2.getId(),
                "missing",
                MarketplaceListingStatus.SOLD
        )).isInstanceOf(MarketplaceListingNotFoundException.class);
    }

    @Test
    void sameSlugInAnotherAccountUnaffected() {
        MarketplaceListing account1Listing =
                listing(account1, "3001", "same-slug");
        MarketplaceListing account2Listing =
                listing(account2, "3002", "same-slug");

        service.updateStatusBySlug(
                account2.getId(),
                "same-slug",
                MarketplaceListingStatus.SOLD
        );

        assertThat(listingRepository.findById(account1Listing.getId())
                .orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(listingRepository.findById(account1Listing.getId())
                .orElseThrow().isCurrent())
                .isTrue();
        assertThat(listingRepository.findById(account2Listing.getId())
                .orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.SOLD);
    }

    @Test
    void locallyNonActiveListingNoLongerRefreshEligible() {
        listing(account2, "4001", "eligible-before");

        List<YagaRefreshCandidateRow> before =
                refreshJobRepository.selectCandidatesForUpdate(
                        account2.getId(),
                        10
                );
        assertThat(before)
                .extracting(YagaRefreshCandidateRow::getExternalListingId)
                .contains("4001");

        service.updateStatusBySlug(
                account2.getId(),
                "eligible-before",
                MarketplaceListingStatus.SOLD
        );

        List<YagaRefreshCandidateRow> after =
                refreshJobRepository.selectCandidatesForUpdate(
                        account2.getId(),
                        10
                );
        assertThat(after)
                .extracting(YagaRefreshCandidateRow::getExternalListingId)
                .doesNotContain("4001");
    }

    @Test
    void reconciliationLeavesPresentListingUnchangedAndMarksAbsentUnavailable() {
        MarketplaceListing present = listing(account2, "5001", "present");
        MarketplaceListing absent = listing(account2, "5002", "absent");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        true,
                        discovered("5001", "present")
                ));

        YagaListingReconciliationResponse response =
                service.reconcile(account2.getId());

        assertThat(response.discoveredPublished()).isEqualTo(1);
        assertThat(response.localCurrentPublished()).isEqualTo(2);
        assertThat(response.unchanged()).isEqualTo(1);
        assertThat(response.unavailable()).isEqualTo(1);
        assertThat(response.remotePublishedAndLocalCurrentPublished())
                .isEqualTo(1);
        assertThat(response.remotePublishedButLocalNonCurrent())
                .isZero();
        assertThat(response.remotePublishedButMissingLocally())
                .isZero();
        assertThat(listingRepository.findById(present.getId())
                .orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        MarketplaceListing unavailable = listingRepository
                .findById(absent.getId()).orElseThrow();
        assertThat(unavailable.getStatus())
                .isEqualTo(MarketplaceListingStatus.UNAVAILABLE);
        assertThat(unavailable.isCurrent()).isFalse();
    }

    @Test
    void incompleteDiscoveryMakesNoChanges() {
        MarketplaceListing listing = listing(account2, "6001", "incomplete");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", false));

        assertThatThrownBy(() -> service.reconcile(account2.getId()))
                .hasMessageContaining("incomplete");

        MarketplaceListing persisted = listingRepository
                .findById(listing.getId()).orElseThrow();
        assertThat(persisted.getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(persisted.isCurrent()).isTrue();
    }

    @Test
    void reconciliationIsAccountScoped() {
        MarketplaceListing account1Listing =
                listing(account1, "7001", "account-1-only");
        MarketplaceListing account2Listing =
                listing(account2, "7002", "account-2-only");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery("w-a-k-a", true));

        service.reconcile(account2.getId());

        assertThat(listingRepository.findById(account1Listing.getId())
                .orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(listingRepository.findById(account1Listing.getId())
                .orElseThrow().isCurrent())
                .isTrue();
        assertThat(listingRepository.findById(account2Listing.getId())
                .orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.UNAVAILABLE);
    }

    @Test
    void diagnosticsClassifyRemotePublishedWithLocalCurrent() {
        listing(account2, "9001", "current");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        true,
                        discovered("9001", "current")
                ));

        YagaListingReconciliationResponse response =
                service.reconcile(account2.getId());

        assertThat(response.remotePublishedAndLocalCurrentPublished())
                .isEqualTo(1);
        assertThat(response.remotePublishedButLocalNonCurrent())
                .isZero();
        assertThat(response.remotePublishedButMissingLocally())
                .isZero();
    }

    @Test
    void diagnosticsClassifyRemotePublishedWithLocalNonCurrent() {
        MarketplaceListing local = listing(account2, "9002", "non-current");
        local.setStatus(MarketplaceListingStatus.UNAVAILABLE);
        local.setCurrent(false);
        listingRepository.saveAndFlush(local);
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        true,
                        discovered("9002", "remote-slug")
                ));

        YagaListingReconciliationResponse response =
                service.reconcile(account2.getId());

        assertThat(response.remotePublishedAndLocalCurrentPublished())
                .isZero();
        assertThat(response.remotePublishedButLocalNonCurrent())
                .isEqualTo(1);
        assertThat(response.remotePublishedButMissingLocally())
                .isZero();
        assertThat(response.remotePublishedButLocalNonCurrentListings())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.externalListingId())
                            .isEqualTo("9002");
                    assertThat(diagnostic.productSlug())
                            .isEqualTo("non-current");
                    assertThat(diagnostic.status())
                            .isEqualTo(MarketplaceListingStatus.UNAVAILABLE);
                    assertThat(diagnostic.current()).isFalse();
                });
    }

    @Test
    void diagnosticsClassifyRemotePublishedMissingLocally() {
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        true,
                        discovered("9003", "missing")
                ));

        YagaListingReconciliationResponse response =
                service.reconcile(account2.getId());

        assertThat(response.remotePublishedAndLocalCurrentPublished())
                .isZero();
        assertThat(response.remotePublishedButLocalNonCurrent())
                .isZero();
        assertThat(response.remotePublishedButMissingLocally())
                .isEqualTo(1);
        assertThat(response.remotePublishedButMissingLocalListings())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.externalListingId())
                            .isEqualTo("9003");
                    assertThat(diagnostic.productSlug())
                            .isEqualTo("missing");
                });
    }

    @Test
    void diagnosticsAreAccountScoped() {
        listing(account1, "9004", "belongs-to-account-1");
        when(discoveryService.discover("w-a-k-a"))
                .thenReturn(discovery(
                        "w-a-k-a",
                        true,
                        discovered("9004", "belongs-to-account-1")
                ));

        YagaListingReconciliationResponse response =
                service.reconcile(account2.getId());

        assertThat(response.remotePublishedAndLocalCurrentPublished())
                .isZero();
        assertThat(response.remotePublishedButLocalNonCurrent())
                .isZero();
        assertThat(response.remotePublishedButMissingLocally())
                .isEqualTo(1);
        assertThat(listingRepository
                .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account1.getId(),
                        Marketplace.YAGA,
                        "9004"
                ).orElseThrow().isCurrent())
                .isTrue();
    }

    @Test
    void localStateOperationsDoNotCallRemoteMutationCollaborators() {
        listing(account2, "8001", "local-only");

        service.updateStatusBySlug(
                account2.getId(),
                "local-only",
                MarketplaceListingStatus.HIDDEN
        );

        verifyNoMoreInteractions(discoveryService);
    }

    private MarketplaceListing listing(
            YagaAccount account,
            String externalId,
            String productSlug
    ) {
        Product product = new Product(
                "SKU-" + account.getId() + "-" + externalId,
                "Product " + externalId
        );
        product.setStatus(ProductStatus.DRAFT);
        product = productRepository.saveAndFlush(product);

        ProductImage image = new ProductImage(product, "drive-" + externalId);
        image.setFileName("image-" + externalId + ".jpg");
        image.setDisplayOrder(0);
        image.setPrimaryImage(true);
        productImageRepository.saveAndFlush(image);

        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                externalId,
                "https://www.yaga.ee/" + account.getShopSlug() +
                        "/toode/" + productSlug
        );
        listing.setYagaAccount(account);
        listing.setShopSlug(account.getShopSlug());
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(true);
        listing.setExternalCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));
        listing.setLastSyncedAt(Instant.parse("2026-09-01T12:00:00Z"));
        MarketplaceListingImage listingImage = new MarketplaceListingImage(
                "external-image-" + externalId,
                "https://images.example/" + externalId + ".jpg",
                "image-" + externalId + ".jpg",
                0
        );
        listingImage.setProductImage(image);
        listing.addImage(listingImage);
        return listingRepository.saveAndFlush(listing);
    }

    private YagaShopDiscoveredListingResponse discovered(
            String externalId,
            String productSlug
    ) {
        return new YagaShopDiscoveredListingResponse(
                externalId,
                productSlug,
                "https://www.yaga.ee/w-a-k-a/toode/" + productSlug,
                Instant.parse("2026-09-01T12:00:00Z"),
                1
        );
    }

    private YagaShopDiscoveryResponse discovery(
            String shopSlug,
            boolean complete,
            YagaShopDiscoveredListingResponse... listings
    ) {
        List<YagaShopDiscoveredListingResponse> activeExisting =
                List.of(listings);
        return new YagaShopDiscoveryResponse(
                shopSlug,
                "API",
                1,
                activeExisting.size(),
                activeExisting.size(),
                activeExisting.size(),
                0,
                activeExisting.size(),
                0,
                0,
                false,
                complete,
                complete
                        ? YagaShopDiscoveryStopReason.CONFIRMED_END
                        : YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                activeExisting.size(),
                activeExisting.size(),
                "TEST",
                true,
                List.of(activeExisting.size()),
                activeExisting.size(),
                activeExisting.size(),
                activeExisting.size(),
                false,
                activeExisting.size(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Boolean.FALSE,
                "TEST",
                false,
                null,
                null,
                "TEST",
                List.of(),
                true,
                List.of(0),
                List.of(activeExisting.size()),
                List.of(),
                activeExisting,
                List.of(),
                List.of()
        );
    }
}
