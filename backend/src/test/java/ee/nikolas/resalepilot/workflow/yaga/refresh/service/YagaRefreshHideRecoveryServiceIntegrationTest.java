package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.*;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.controller.YagaRefreshHideRecoveryController;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class YagaRefreshHideRecoveryServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("resalepilot").withUsername("resalepilot").withPassword("resalepilot");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired YagaRefreshHideRecoveryService service;
    @Autowired YagaRefreshRunRepository runs;
    @Autowired YagaRefreshJobRepository jobs;
    @Autowired YagaAccountRepository accounts;
    @Autowired ProductRepository products;
    @Autowired MarketplaceListingRepository listings;
    @Autowired PlatformTransactionManager transactions;
    private YagaRefreshRun run;
    private YagaRefreshJob job;
    private MarketplaceListing oldListing;
    private MarketplaceListing replacement;
    private YagaAccount account;
    private Product product;
    private final Instant now = Instant.parse("2026-09-29T19:37:26Z");

    @BeforeEach
    void fixture() {
        jobs.deleteAllInBatch();
        runs.deleteAllInBatch();
        listings.deleteAllInBatch();
        products.deleteAllInBatch();
        account = accounts.saveAndFlush(new YagaAccount("Recovery", "shop-" + UUID.randomUUID(), null, 5));
        account.setEnabled(true);
        account = accounts.saveAndFlush(account);
        product = products.saveAndFlush(new Product("RECOVERY-001", "Recovery product"));
        oldListing = listing(account, product, "old-slug", "100", true);
        replacement = listing(account, product, "new-slug", "200", false);
        run = new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 1, null, now);
        run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        run.setCompletedAt(now);
        run.setSelectedJobCount(1);
        run.setLastErrorCode("AUTO_REFRESH_STEP_FAILED");
        job = new YagaRefreshJob(product, oldListing, "100", account.getShopSlug(), "old-slug", oldListing.getExternalUrl(),
                product.getTitle(), now, now, 0, 0, 0, now);
        job.setNewListing(replacement);
        job.setNewExternalListingId("200");
        job.setNewShopSlug(account.getShopSlug());
        job.setNewProductSlug("new-slug");
        job.setNewProductUrl(replacement.getExternalUrl());
        job.setPublicationStatus("PUBLISHED");
        job.setPublicationConfirmedAt(now);
        job.setPublicationPreparationId(UUID.randomUUID());
        job.setStatus(YagaRefreshJobStatus.FAILED);
        job.setCompletedAt(now);
        job.setLastErrorCode("AUTO_REFRESH_STEP_FAILED");
        run.addJob(job);
        run = runs.saveAndFlush(run);
        job = run.getJobs().getFirst();
    }

    @Test
    void endpointReopensOnlyWorkflowAndRepeatIsNoOp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new YagaRefreshHideRecoveryController(service)).build();
        String url = "/api/yaga/refresh-runs/" + run.getId() + "/jobs/" + job.getId() + "/recover-hide-preparation";
        mvc.perform(post(url)).andExpect(status().isOk()).andExpect(jsonPath("recovered").value(true))
                .andExpect(jsonPath("jobStatus").value("NEW_LISTING_CONFIRMED"));
        var first = jobs.findById(job.getId()).orElseThrow();
        var version = first.getVersion();
        mvc.perform(post(url)).andExpect(status().isOk()).andExpect(jsonPath("recovered").value(false));
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(version);
        assertThat(saved.getNewListing().getId()).isEqualTo(replacement.getId());
        assertThat(saved.getPublicationStatus()).isEqualTo("PUBLISHED");
        assertThat(saved.getPublicationPreparationId()).isEqualTo(job.getPublicationPreparationId());
        assertThat(saved.getPublicationConfirmedAt()).isEqualTo(now);
        assertThat(saved.getNewExternalListingId()).isEqualTo("200");
        assertThat(saved.getNewProductSlug()).isEqualTo("new-slug");
        assertThat(saved.getNewProductUrl()).isEqualTo(replacement.getExternalUrl());
        assertThat(saved.getHideStatus()).isEqualTo(YagaRefreshHideStatus.NOT_STARTED);
        assertThat(saved.getHidePreparationId()).isNull();
        assertThat(saved.getCompletedAt()).isNull();
        assertThat(saved.getLastErrorCode()).isNull();
        var savedRun = runs.findById(run.getId()).orElseThrow();
        assertThat(savedRun.getStatus()).isEqualTo(YagaRefreshRunStatus.PROCESSING);
        assertThat(savedRun.getCompletedAt()).isNull();
        assertThat(listings.findById(oldListing.getId()).orElseThrow().isCurrent()).isTrue();
        assertThat(listings.findById(replacement.getId()).orElseThrow().isCurrent()).isFalse();
        assertThat(listings.findById(oldListing.getId()).orElseThrow().getVersion()).isEqualTo(oldListing.getVersion());
        assertThat(listings.findById(replacement.getId()).orElseThrow().getVersion()).isEqualTo(replacement.getVersion());
        assertThat(listings.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"publication", "missingReplacement", "jobState", "runState", "hideState", "prepared", "preparationId",
            "confirmStarted", "confirmed", "wrongProduct", "wrongAccount", "snapshot", "oldInactive", "newInactive", "newCurrent", "disabled"})
    void invalidStateRejectsWithoutRecovery(String invalid) {
        mutate(j -> {
            switch (invalid) {
                case "publication" -> j.setPublicationStatus("PUBLISH_RESULT_UNKNOWN");
                case "missingReplacement" -> j.setNewListing(null);
                case "jobState" -> j.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                case "runState" -> j.getRun().setStatus(YagaRefreshRunStatus.CANCELLED);
                case "hideState" -> j.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
                case "prepared" -> j.setHidePreparedAt(now);
                case "preparationId" -> j.setHidePreparationId(UUID.randomUUID());
                case "confirmStarted" -> j.setHideConfirmStartedAt(now);
                case "confirmed" -> j.setHideConfirmedAt(now);
                case "wrongProduct" -> j.getNewListing().setProduct(products.save(new Product("OTHER", "Other")));
                case "wrongAccount" -> j.getNewListing().setYagaAccount(accounts.save(new YagaAccount("Other", "other-" + UUID.randomUUID(), null, 5)));
                case "snapshot" -> j.setNewProductSlug("different");
                case "oldInactive" -> j.getOldListing().setCurrent(false);
                case "newInactive" -> j.getNewListing().setStatus(MarketplaceListingStatus.HIDDEN);
                case "newCurrent" -> {
                    j.getOldListing().setCurrent(false);
                    listings.flush();
                    j.getNewListing().setCurrent(true);
                }
                case "disabled" -> j.getRun().getYagaAccount().setEnabled(false);
            }
        });
        var version = jobs.findById(job.getId()).orElseThrow().getVersion();
        assertThatThrownBy(() -> service.recover(run.getId(), job.getId())).isInstanceOf(YagaRefreshInvalidStateException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getVersion()).isEqualTo(version);
        assertThat(jobs.findById(job.getId()).orElseThrow().getCompletedAt()).isEqualTo(now);
        assertThat(runs.findById(run.getId()).orElseThrow().getCompletedAt()).isEqualTo(now);
    }

    @Test
    void unknownJobOrWrongRunRejected() {
        assertThatThrownBy(() -> service.recover(run.getId(), UUID.randomUUID())).isInstanceOf(YagaRefreshJobNotFoundException.class);
        assertThatThrownBy(() -> service.recover(UUID.randomUUID(), job.getId())).isInstanceOf(YagaRefreshRunNotFoundException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
    }

    @Test
    void otherActiveAccountRunBlocksRecovery() {
        runs.saveAndFlush(new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 1, null, now));
        assertThatThrownBy(() -> service.recover(run.getId(), job.getId())).isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("Another refresh run");
    }

    @Test
    void failedTerminalRunIsRecoverable() {
        mutate(j -> j.getRun().setStatus(YagaRefreshRunStatus.FAILED));
        assertThat(service.recover(run.getId(), job.getId()).recovered()).isTrue();
    }

    @Test
    void repeatedRecoveryAfterHidePreparationDoesNotResetIt() {
        service.recover(run.getId(), job.getId());
        UUID preparationId = UUID.randomUUID();
        mutate(j -> {
            j.setStatus(YagaRefreshJobStatus.HIDING_OLD);
            j.setHideStatus(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
            j.setHidePreparationId(preparationId);
            j.setHidePreparedAt(now);
        });
        assertThatThrownBy(() -> service.recover(run.getId(), job.getId())).isInstanceOf(YagaRefreshInvalidStateException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getHidePreparationId()).isEqualTo(preparationId);
    }

    @Test
    void unrelatedAccountRunDoesNotBlockRecovery() {
        var otherAccount = accounts.saveAndFlush(new YagaAccount("Other", "other-" + UUID.randomUUID(), null, 5));
        runs.saveAndFlush(new YagaRefreshRun(otherAccount, YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 1, null, now));
        assertThat(service.recover(run.getId(), job.getId()).recovered()).isTrue();
    }

    @Test
    void jobCannotBeRecoveredThroughAnotherExistingRun() {
        var other = new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 1, null, now);
        other.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        other = runs.saveAndFlush(other);
        UUID otherId = other.getId();
        assertThatThrownBy(() -> service.recover(otherId, job.getId())).isInstanceOf(YagaRefreshJobNotFoundException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
    }

    @Test
    void unresolvedProductJobInTerminalRunBlocksRecovery() {
        var other = new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 1, null, now);
        other.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        var unresolved = new YagaRefreshJob(product, oldListing, "100", account.getShopSlug(), "old-slug", oldListing.getExternalUrl(),
                product.getTitle(), now, now, 0, 0, 0, now);
        unresolved.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
        other.addJob(unresolved);
        runs.saveAndFlush(other);
        assertThatThrownBy(() -> service.recover(run.getId(), job.getId())).isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("Another refresh job");
    }

    @Test
    void parallelRecoveryReopensExactlyOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.recover(run.getId(), job.getId()); });
            var second = pool.submit(() -> { start.await(); return service.recover(run.getId(), job.getId()); });
            start.countDown();
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS).recovered(), second.get(20, TimeUnit.SECONDS).recovered()))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private void mutate(Consumer<YagaRefreshJob> change) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                change.accept(runs.findForUpdateWithJobsById(run.getId()).orElseThrow().getJobs().getFirst()));
    }

    private MarketplaceListing listing(YagaAccount account, Product product, String slug, String externalId, boolean current) {
        var listing = new MarketplaceListing(product, Marketplace.YAGA, externalId,
                "https://www.yaga.ee/" + account.getShopSlug() + "/toode/" + slug);
        listing.setYagaAccount(account);
        listing.setShopSlug(account.getShopSlug());
        listing.setProductSlug(slug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(current);
        return listings.saveAndFlush(listing);
    }
}
