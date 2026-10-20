package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountRepository;
import ee.nikolas.resalepilot.workflow.yaga.hiding.YagaHidingSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class YagaRefreshHidingFinalizationIntegrationTest {

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

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-12T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private YagaAccountRepository accountRepository;

    @Autowired
    private YagaRefreshRunRepository runRepository;

    @Autowired
    private YagaRefreshJobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private YagaPageDataClient pageDataClient;
    private YagaHidingSessionManager sessionManager;
    private YagaRefreshHidingExecutionService service;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAllInBatch();
        runRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();

        pageDataClient = mock(YagaPageDataClient.class);
        sessionManager = mock(YagaHidingSessionManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaHidingSessionManager> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sessionManager);
        service = new YagaRefreshHidingExecutionService(
                runRepository,
                listingRepository,
                provider,
                pageDataClient,
                transactionManager,
                clock
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void orderedFinalizationRespectsCurrentListingUniqueConstraint(boolean precedingSkip) {
        Product product = new Product("RF-FINALIZE-1", "Title");
        product.setStatus(ProductStatus.DRAFT);
        product = productRepository.saveAndFlush(product);

        MarketplaceListing oldListing = listing(
                product,
                "1001",
                "old-listing",
                true
        );
        MarketplaceListing newListing = listing(
                product,
                "1002",
                "new-listing",
                false
        );

        YagaRefreshRun run = new YagaRefreshRun(
                accountRepository.findByShopSlug("nik-ar").orElseThrow(),
                YagaRefreshTriggerType.MANUAL,
                YagaRefreshRunMode.MANUAL,
                1,
                "ordered-finalization",
                clock.instant()
        );
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        run.setSelectedJobCount(1);
        YagaRefreshJob job = new YagaRefreshJob(
                product,
                oldListing,
                oldListing.getExternalListingId(),
                oldListing.getShopSlug(),
                oldListing.getProductSlug(),
                oldListing.getExternalUrl(),
                product.getTitle(),
                oldListing.getExternalCreatedAt(),
                oldListing.getCreatedAt(),
                0,
                0,
                0,
                clock.instant()
        );
        job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
        job.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
        job.setHideConfirmStartedAt(clock.instant());
        job.setNewListing(newListing);
        job.setNewExternalListingId(newListing.getExternalListingId());
        job.setNewShopSlug(newListing.getShopSlug());
        job.setNewProductSlug(newListing.getProductSlug());
        job.setNewProductUrl(newListing.getExternalUrl());
        if (precedingSkip) {
            YagaRefreshJob skipped = new YagaRefreshJob(product, oldListing,
                    oldListing.getExternalListingId(), oldListing.getShopSlug(),
                    oldListing.getProductSlug(), oldListing.getExternalUrl(), product.getTitle(),
                    oldListing.getExternalCreatedAt(), oldListing.getCreatedAt(), 0, 0, 0, clock.instant());
            YagaSelectedJobLifecycle.finish(skipped, YagaRefreshJobStatus.SKIPPED, clock.instant());
            skipped.setLastErrorCode("SOURCE_NOT_ACTIVE");
            run.addJob(skipped);
            job.setSelectionOrder(1);
            run.setSelectedJobCount(2);
        }
        run.addJob(job);
        run = runRepository.saveAndFlush(run);

        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(data(1001L, "old-listing", "hidden"));
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(data(1002L, "new-listing", "published"));

        service.reconcile(run.getId(), job.getId());
        entityManager.clear();

        MarketplaceListing persistedOld = listingRepository
                .findById(oldListing.getId()).orElseThrow();
        MarketplaceListing persistedNew = listingRepository
                .findById(newListing.getId()).orElseThrow();
        YagaRefreshRun persistedRun = runRepository
                .findWithJobsById(run.getId()).orElseThrow();
        YagaRefreshJob persistedJob = persistedRun.getJobs().getLast();

        assertThat(persistedOld.isCurrent()).isFalse();
        assertThat(persistedOld.getStatus())
                .isEqualTo(MarketplaceListingStatus.HIDDEN);
        assertThat(persistedNew.isCurrent()).isTrue();
        assertThat(persistedNew.getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(persistedJob.getStatus())
                .isEqualTo(YagaRefreshJobStatus.COMPLETED);
        assertThat(persistedJob.getHideStatus())
                .isEqualTo(YagaRefreshHideStatus.HIDDEN);
        assertThat(persistedRun.getStatus())
                .isEqualTo(precedingSkip ? YagaRefreshRunStatus.COMPLETED_WITH_ERRORS
                        : YagaRefreshRunStatus.COMPLETED);
        verifyNoInteractions(sessionManager);
    }

    @Test
    void inactiveBatchSkipsPersistAndFinalizesWithoutPublicationOrHide() {
        var account = accountRepository.findByShopSlug("nik-ar").orElseThrow();
        YagaRefreshRun run = new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND,
                YagaRefreshRunMode.AUTO, 2, "inactive-batch", clock.instant());
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        run.setSelectedJobCount(2);
        for (int i = 0; i < 2; i++) {
            Product product = productRepository.saveAndFlush(new Product("INACTIVE-" + i, "Title"));
            MarketplaceListing listing = listing(product, "200" + i, "inactive-" + i, true);
            run.addJob(new YagaRefreshJob(product, listing, listing.getExternalListingId(),
                    listing.getShopSlug(), listing.getProductSlug(), listing.getExternalUrl(),
                    product.getTitle(), listing.getExternalCreatedAt(), listing.getCreatedAt(),
                    0, 0, i, clock.instant()));
            when(pageDataClient.getProduct(listing.getExternalUrl()))
                    .thenReturn(data(2000L + i, "inactive-" + i, "sold"));
        }
        run = runRepository.saveAndFlush(run);
        var runService = mock(YagaRefreshRunService.class);
        var publication = mock(YagaRefreshExecutionService.class);
        var response = new ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse(
                run.getId(), run.getTriggerType(), run.getMode(), run.getStatus(),
                2, 2, run.getCreatedAt(), null, List.of());
        when(runService.startOnDemandAutoRun(account.getId(), "inactive-batch", 2))
                .thenReturn(java.util.Optional.of(response));
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaRefreshHidingExecutionService> hiding = mock(ObjectProvider.class);
        var orchestration = new YagaRefreshAutoOrchestrationService(
                new ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshSchedulerProperties(
                        true, "0 0 3 * * *", java.time.ZoneId.of("UTC"), 2),
                runService, publication, hiding, runRepository, transactionManager, clock,
                new YagaRefreshSourceValidationService(pageDataClient));

        orchestration.runOnDemand(account.getId(), 2, "inactive-batch");
        YagaRefreshRun persisted = runRepository.findWithJobsById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(persisted.getCompletedAt()).isEqualTo(clock.instant());
        assertThat(persisted.getJobs()).allSatisfy(job -> {
            assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
            assertThat(job.getLastErrorCode()).isEqualTo("SOURCE_NOT_ACTIVE");
            assertThat(job.getPublicationPreparationId()).isNull();
            assertThat(job.getPublicationConfirmStartedAt()).isNull();
            assertThat(job.getNewListing()).isNull();
            assertThat(job.getHideStatus()).isEqualTo(YagaRefreshHideStatus.NOT_STARTED);
        });
        assertThat(listingRepository.findAll()).allSatisfy(listing -> {
            assertThat(listing.getStatus()).isEqualTo(MarketplaceListingStatus.PUBLISHED);
            assertThat(listing.isCurrent()).isTrue();
        });
        verifyNoInteractions(publication, hiding, sessionManager);
    }

    private MarketplaceListing listing(
            Product product,
            String externalId,
            String slug,
            boolean current
    ) {
        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                externalId,
                "https://www.yaga.ee/nik-ar/toode/" + slug
        );
        listing.setShopSlug("nik-ar");
        listing.setYagaAccount(accountRepository.findByShopSlug("nik-ar")
                .orElseThrow());
        listing.setProductSlug(slug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(current);
        listing.setExternalCreatedAt(clock.instant());
        return listingRepository.saveAndFlush(listing);
    }

    private YagaImportedProductData data(
            Long id,
            String slug,
            String status
    ) {
        return new YagaImportedProductData(
                id,
                "nik-ar",
                slug,
                "title",
                "description",
                BigDecimal.ONE,
                "EUR",
                status,
                null,
                List.of(),
                List.of(),
                clock.instant(),
                null,
                null,
                null
        );
    }
}
