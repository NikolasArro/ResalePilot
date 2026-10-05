package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import ee.nikolas.resalepilot.marketplace.entity.*;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.*;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.*;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaManualListingReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "yaga.publishing.enabled=true")
@Testcontainers
class YagaManualListingReconciliationServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("resalepilot").withUsername("resalepilot").withPassword("resalepilot");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired YagaManualListingReconciliationService service;
    @Autowired YagaRefreshJobRepository jobs;
    @Autowired YagaRefreshRunRepository runs;
    @Autowired MarketplaceListingRepository listings;
    @Autowired ProductImageRepository productImages;
    @Autowired ProductRepository products;
    @Autowired YagaAccountRepository accounts;
    @MockitoBean YagaPageDataClient pages;
    @MockitoBean YagaOrderedImageVerifier imageVerifier;
    @MockitoBean GoogleDriveService drive;

    private final Instant now = Instant.parse("2026-10-04T09:00:00Z");
    private YagaAccount account;
    private Product product;
    private MarketplaceListing source;
    private MarketplaceListing firstReplacement;
    private MarketplaceListing secondReplacement;
    private List<YagaImportedProductData.Image> sourceImages;
    private List<YagaImportedProductData.Image> manualImages;
    private List<java.util.UUID> jobIds;
    private YagaManualListingReconcileRequest request;

    @BeforeEach
    void fixture() {
        jobs.deleteAllInBatch();
        runs.deleteAllInBatch();
        listings.deleteAllInBatch();
        productImages.deleteAllInBatch();
        products.deleteAllInBatch();
        reset(pages, imageVerifier);
        account = accounts.findByShopSlug("w-a-k-a").orElseGet(() ->
                accounts.saveAndFlush(new YagaAccount("Manual shop", "w-a-k-a", null, 5)));
        product = new Product("MANUAL-332", "Sparkly dress");
        product.setDescription("Sparkly dress\n\nFull matching description");
        product.setAskingPrice(BigDecimal.TEN);
        product.setCondition(ProductCondition.NEW_WITHOUT_TAGS);
        product = products.saveAndFlush(product);
        for (int index = 0; index < 2; index++) {
            ProductImage image = new ProductImage(product, "drive-" + index);
            image.setDisplayOrder(index);
            image.setPrimaryImage(index == 0);
            productImages.saveAndFlush(image);
        }
        source = listing("100", "old", true);
        firstReplacement = listing("200", "replacement-one", false);
        secondReplacement = listing("300", "replacement-two", false);
        jobIds = List.of(failedPublishedJob(firstReplacement), failedPublishedJob(secondReplacement));
        sourceImages = images("old");
        manualImages = images("manual");
        request = new YagaManualListingReconcileRequest(source.getId(), 400L,
                "https://www.yaga.ee/w-a-k-a/toode/manual");
        when(pages.getProduct(source.getExternalUrl())).thenReturn(remote(source, "deleted", now, sourceImages));
        when(pages.getProduct(firstReplacement.getExternalUrl()))
                .thenReturn(remote(firstReplacement, "deleted", now, images("replacement-one")));
        when(pages.getProduct(secondReplacement.getExternalUrl()))
                .thenReturn(remote(secondReplacement, "deleted", now, images("replacement-two")));
        when(pages.getProduct(request.publicUrl()))
                .thenReturn(new YagaImportedProductData(400L, "w-a-k-a", "manual", product.getTitle(),
                        product.getDescription(), new BigDecimal("16"), "EUR", "published",
                        new YagaImportedProductData.Condition(1L, "Uus"), categories(), manualImages,
                        now, now, null, null));
    }

    @Test
    void attachesManualPublicationAndKeepsFailedJobsUnchangedOnRepeat() {
        var originalJobs = jobIds.stream().map(id -> jobs.findById(id).orElseThrow())
                .map(job -> job.getVersion()).toList();

        var result = service.reconcile(account.getId(), product.getId(), request);

        assertThat(result.reconciled()).isTrue();
        assertThat(result.externalListingId()).isEqualTo("400");
        assertThat(result.inactiveListingIds()).containsExactly(source.getId(), firstReplacement.getId(),
                secondReplacement.getId());
        var manual = listings.findById(result.currentListingId()).orElseThrow();
        assertThat(manual.getProduct().getId()).isEqualTo(product.getId());
        assertThat(manual.getYagaAccount().getId()).isEqualTo(account.getId());
        assertThat(manual.getStatus()).isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(manual.isCurrent()).isTrue();
        assertThat(manual.getExternalUrl()).isEqualTo(request.publicUrl());
        for (var old : List.of(source, firstReplacement, secondReplacement)) {
            var saved = listings.findById(old.getId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(MarketplaceListingStatus.DELETED);
            assertThat(saved.isCurrent()).isFalse();
            assertThat(saved.getDeletedAt()).isEqualTo(now);
        }
        assertThat(listings.findAllByProductIdAndYagaAccountIdAndMarketplaceOrderByIdAsc(
                product.getId(), account.getId(), Marketplace.YAGA)).filteredOn(MarketplaceListing::isCurrent)
                .extracting(MarketplaceListing::getId).containsExactly(manual.getId());
        verify(imageVerifier).verify(eq("old"), eq(sourceImages), eq("manual"), eq(manualImages));

        var repeated = service.reconcile(account.getId(), product.getId(), request);
        assertThat(repeated.reconciled()).isFalse();
        assertThat(repeated.currentListingId()).isEqualTo(manual.getId());
        assertThat(listings.count()).isEqualTo(4);
        for (int index = 0; index < jobIds.size(); index++) {
            var savedJob = jobs.findById(jobIds.get(index)).orElseThrow();
            assertThat(savedJob.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
            assertThat(savedJob.getPublicationStatus()).isEqualTo("PUBLISHED");
            assertThat(savedJob.getVersion()).isEqualTo(originalJobs.get(index));
        }
    }

    @Test
    void rejectsActiveRecordedReplacementWithoutLocalChanges() {
        when(pages.getProduct(secondReplacement.getExternalUrl()))
                .thenReturn(remote(secondReplacement, "published", null, images("replacement-two")));

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("not verified deleted");
        assertUnchanged();
        verifyNoInteractions(imageVerifier);
    }

    @Test
    void rejectsActiveSourceWithoutLocalChanges() {
        when(pages.getProduct(source.getExternalUrl()))
                .thenReturn(remote(source, "published", null, sourceImages));

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("not verified deleted");
        assertUnchanged();
        verifyNoInteractions(imageVerifier);
    }

    @Test
    void rejectsWrongManualIdentityWithoutLocalChanges() {
        when(pages.getProduct(request.publicUrl()))
                .thenReturn(new YagaImportedProductData(401L, "w-a-k-a", "manual", product.getTitle(),
                        product.getDescription(), new BigDecimal("16"), "EUR", "published",
                        null, categories(), manualImages, now, now, null, null));

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("not the expected published listing");
        assertUnchanged();
    }

    @Test
    void rejectsManualListingFromAnotherShopWithoutLocalChanges() {
        when(pages.getProduct(request.publicUrl()))
                .thenReturn(new YagaImportedProductData(400L, "nik-ar", "manual", product.getTitle(),
                        product.getDescription(), new BigDecimal("16"), "EUR", "published",
                        null, categories(), manualImages, now, now, null, null));

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("not the expected published listing");
        assertUnchanged();
    }

    @Test
    void rejectsImageMismatchWithoutLocalChanges() {
        doThrow(new YagaRefreshInvalidStateException("Yaga image identity or order differs"))
                .when(imageVerifier).verify(anyString(), anyList(), anyString(), anyList());

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("image identity or order differs");
        assertUnchanged();
    }

    @Test
    void changedManualImagesDuringAttachmentRollBackTheNewListing() {
        var changedImages = images("changed");
        when(pages.getProduct(request.publicUrl())).thenReturn(
                new YagaImportedProductData(400L, "w-a-k-a", "manual", product.getTitle(),
                        product.getDescription(), new BigDecimal("16"), "EUR", "published",
                        null, categories(), manualImages, now, now, null, null),
                new YagaImportedProductData(400L, "w-a-k-a", "manual", product.getTitle(),
                        product.getDescription(), new BigDecimal("16"), "EUR", "published",
                        null, categories(), changedImages, now, now, null, null));

        assertThatThrownBy(() -> service.reconcile(account.getId(), product.getId(), request))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("image attachment changed");
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(listings.count()).isEqualTo(3);
        assertThat(listings.findById(source.getId()).orElseThrow().isCurrent()).isTrue();
        assertThat(listings.findById(source.getId()).orElseThrow().getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        for (var listing : List.of(firstReplacement, secondReplacement)) {
            assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
                    .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        }
    }

    private MarketplaceListing listing(String externalId, String slug, boolean current) {
        MarketplaceListing listing = new MarketplaceListing(product, Marketplace.YAGA, externalId,
                "https://www.yaga.ee/w-a-k-a/toode/" + slug);
        listing.setYagaAccount(account);
        listing.setShopSlug("w-a-k-a");
        listing.setProductSlug(slug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(current);
        listing.addCategory(new MarketplaceListingCategory(0, 1L, null, "Naistele"));
        var originals = productImages.findAllByProductIdOrderByDisplayOrderAsc(product.getId());
        for (int index = 0; index < originals.size(); index++) {
            MarketplaceListingImage image = new MarketplaceListingImage(slug + index,
                    "https://images.yaga.ee/" + slug + "/" + slug + index + ".jpeg",
                    slug + index + ".jpeg", index);
            image.setProductImage(originals.get(index));
            listing.addImage(image);
        }
        return listings.saveAndFlush(listing);
    }

    private java.util.UUID failedPublishedJob(MarketplaceListing replacement) {
        YagaRefreshRun run = new YagaRefreshRun(account, YagaRefreshTriggerType.ON_DEMAND,
                YagaRefreshRunMode.AUTO, 1, null, now);
        run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        run.setSelectedJobCount(1);
        YagaRefreshJob job = new YagaRefreshJob(product, source, source.getExternalListingId(),
                source.getShopSlug(), source.getProductSlug(), source.getExternalUrl(), product.getTitle(),
                now, now, 0, 2, 2, now);
        job.setStatus(YagaRefreshJobStatus.FAILED);
        job.setPublicationStatus("PUBLISHED");
        job.setNewListing(replacement);
        job.setNewExternalListingId(replacement.getExternalListingId());
        job.setNewShopSlug(replacement.getShopSlug());
        job.setNewProductSlug(replacement.getProductSlug());
        job.setNewProductUrl(replacement.getExternalUrl());
        run.addJob(job);
        return runs.saveAndFlush(run).getJobs().getFirst().getId();
    }

    private YagaImportedProductData remote(MarketplaceListing listing, String status, Instant deletedAt,
            List<YagaImportedProductData.Image> images) {
        return new YagaImportedProductData(Long.valueOf(listing.getExternalListingId()),
                listing.getShopSlug(), listing.getProductSlug(), product.getTitle(), product.getDescription(),
                BigDecimal.TEN, "EUR", status, null, categories(), images, now, now, null, deletedAt);
    }

    private List<YagaImportedProductData.Category> categories() {
        return List.of(new YagaImportedProductData.Category(1L, null, "Naistele", List.of()));
    }

    private List<YagaImportedProductData.Image> images(String slug) {
        return List.of(new YagaImportedProductData.Image(slug + "0",
                        "https://images.yaga.ee/" + slug + "/" + slug + "0.jpeg", slug + "0.jpeg"),
                new YagaImportedProductData.Image(slug + "1",
                        "https://images.yaga.ee/" + slug + "/" + slug + "1.jpeg", slug + "1.jpeg"));
    }
}
