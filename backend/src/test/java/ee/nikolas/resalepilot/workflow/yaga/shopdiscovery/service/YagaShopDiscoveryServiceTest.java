package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.client.YagaShopPageClient;
import ee.nikolas.resalepilot.integration.yaga.client.YagaShopPageClient.YagaShopListingSourceException;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage.YagaShopPageDiagnostics;
import ee.nikolas.resalepilot.integration.yaga.parser.YagaPageDataParser;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.config.YagaShopDiscoveryProperties;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryException;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaProductTitleResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YagaShopDiscoveryServiceTest {

    @Test
    void discoversSeveralPagesUntilLastPage() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a", "b");
        fixture.next(1, "https://www.yaga.ee/nik-ar?page=2");
        fixture.shopPage(2, "c");
        fixture.pageData("a", data("published", "nik-ar", "a"));
        fixture.pageData("b", data("published", "nik-ar", "b"));
        fixture.pageData("c", data("published", "nik-ar", "c"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.pagesVisited()).isEqualTo(2);
        assertThat(response.cardsDiscovered()).isEqualTo(3);
        assertThat(response.uniqueCandidates()).isEqualTo(3);
        assertThat(response.activeNewCount()).isEqualTo(3);
        assertThat(response.truncated()).isFalse();
        assertThat(response.activeNew())
                .extracting("productSlug")
                .containsExactly("a", "b", "c");
    }

    @Test
    void duplicateCardIsSkippedAndDoesNotFetchDetailTwice() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a", "a");
        fixture.pageData("a", data("published", "nik-ar", "a"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(1);
        assertThat(response.skipped())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.DUPLICATE);
        assertThat(fixture.pageDataCalls("a")).isEqualTo(1);
    }

    @Test
    void paginationLoopIsTruncated() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a");
        fixture.next(1, "https://www.yaga.ee/nik-ar?page=2");
        fixture.shopPage(2, "b");
        fixture.next(2, "https://www.yaga.ee/nik-ar");
        fixture.pageData("a", data("published", "nik-ar", "a"));
        fixture.pageData("b", data("published", "nik-ar", "b"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.pagesVisited()).isEqualTo(2);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void maxPagesAndMaxListingsBoundDiscovery() {
        Fixture byPages = new Fixture(1, 100);
        byPages.shopPage(1, "a");
        byPages.next(1, "https://www.yaga.ee/nik-ar?page=2");
        byPages.pageData("a", data("published", "nik-ar", "a"));

        assertThat(byPages.service().discover("nik-ar").truncated())
                .isTrue();

        Fixture byListings = new Fixture(10, 1);
        byListings.shopPage(1, "a", "b");
        byListings.pageData("a", data("published", "nik-ar", "a"));

        YagaShopDiscoveryResponse response =
                byListings.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(1);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void activePublishedIsSplitIntoNewAndExisting() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "new-slug", "existing-slug");
        fixture.pageData("new-slug", data("published", "nik-ar", "new-slug"));
        fixture.pageData("existing-slug", data("published", "nik-ar", "existing-slug"));
        MarketplaceListing existing = mock(MarketplaceListing.class);
        when(fixture.listingRepository
                .findByMarketplaceAndShopSlugAndProductSlug(
                        Marketplace.YAGA,
                        "nik-ar",
                        "existing-slug"
                ))
                .thenReturn(Optional.of(existing));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.activePublishedCount()).isEqualTo(2);
        assertThat(response.activeNew())
                .extracting("productSlug")
                .containsExactly("new-slug");
        assertThat(response.activeExisting())
                .extracting("productSlug")
                .containsExactly("existing-slug");
    }

    @Test
    void inactiveStatusesAreSkippedAndUnknownStatusFails() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "sold", "hidden", "not-visible", "deleted", "unknown");
        fixture.pageData("sold", data("sold", "nik-ar", "sold"));
        fixture.pageData("hidden", data("hidden", "nik-ar", "hidden"));
        fixture.pageData("not-visible", data("not-visible", "nik-ar", "not-visible"));
        fixture.pageData("deleted", dataWithDeletedAt("deleted"));
        fixture.pageData("unknown", data("mystery", "nik-ar", "unknown"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.skipped())
                .extracting("reason")
                .containsExactly(
                        YagaShopDiscoveryReason.SOLD,
                        YagaShopDiscoveryReason.HIDDEN,
                        YagaShopDiscoveryReason.NOT_VISIBLE,
                        YagaShopDiscoveryReason.DELETED
                );
        assertThat(response.failed())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.UNKNOWN_STATUS);
        assertThat(response.activePublishedCount()).isZero();
    }

    @Test
    void wrongShopAndInvalidDataDoNotBecomeActive() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "wrong-shop", "bad-data");
        fixture.pageData("wrong-shop", data("published", "other-shop", "wrong-shop"));
        fixture.pageData("bad-data", new YagaImportedProductData(
                null,
                "nik-ar",
                "bad-data",
                null,
                BigDecimal.ONE,
                "EUR",
                "published",
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null
        ));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.skipped())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.WRONG_SHOP);
        assertThat(response.failed())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.INVALID_DATA);
    }

    @Test
    void detailFetchFailureDoesNotAbortWholeDiscovery() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "bad", "good");
        fixture.failPageData("bad");
        fixture.pageData("good", data("published", "nik-ar", "good"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.failed())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.FETCH_FAILED);
        assertThat(response.activeNew())
                .extracting("productSlug")
                .containsExactly("good");
    }

    @Test
    void unknownShopListingSourceReturnsControlledDiagnostics() {
        Fixture fixture = new Fixture(10, 100);
        fixture.failShopSource();

        assertThatThrownBy(() -> fixture.service().discover("nik-ar"))
                .isInstanceOf(YagaShopDiscoveryException.class)
                .hasMessage("Yaga shop listing source could not be identified")
                .extracting(exception ->
                        ((YagaShopDiscoveryException) exception)
                                .getDetails()
                                .get("nextDataPresent"))
                .isEqualTo("true");
    }

    @Test
    void invalidShopSlugIsRejected() {
        Fixture fixture = new Fixture(10, 100);

        assertThatThrownBy(() ->
                fixture.service().discover("../nik-ar"))
                .isInstanceOf(
                        YagaShopDiscoveryRequestInvalidException.class
                );
    }

    @Test
    void discoveryDoesNotMutateRepositories() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a");
        fixture.pageData("a", data("published", "nik-ar", "a"));

        fixture.service().discover("nik-ar");

        verify(fixture.listingRepository, never()).save(any());
        verify(fixture.listingRepository, never()).saveAndFlush(any());
        verify(fixture.listingRepository, never()).delete(any());
        verify(fixture.listingRepository, never()).deleteAll();
    }

    @Test
    void shopDiscoveryWorkflowDoesNotImportDriveOrPlaywright() throws Exception {
        Path root = Path.of(
                "src/main/java/ee/nikolas/resalepilot/workflow/yaga/shopdiscovery"
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

    private static YagaImportedProductData data(
            String status,
            String shopSlug,
            String productSlug
    ) {
        return new YagaImportedProductData(
                (long) Math.abs(productSlug.hashCode()),
                shopSlug,
                productSlug,
                null,
                BigDecimal.ONE,
                "EUR",
                status,
                null,
                List.of(),
                List.of(new YagaImportedProductData.Image(
                        "image-" + productSlug,
                        "https://images.yaga.ee/" + productSlug + ".jpg",
                        productSlug + ".jpg"
                )),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null
        );
    }

    private static YagaImportedProductData dataWithDeletedAt(
            String productSlug
    ) {
        YagaImportedProductData base =
                data("published", "nik-ar", productSlug);
        return new YagaImportedProductData(
                base.externalId(),
                base.shopSlug(),
                base.productSlug(),
                base.description(),
                base.price(),
                base.currency(),
                base.status(),
                base.condition(),
                base.categoryPath(),
                base.images(),
                base.createdAt(),
                base.updatedAt(),
                base.hiddenAt(),
                Instant.parse("2026-01-02T00:00:00Z")
        );
    }

    private static class Fixture {
        private final FakeShopPageClient shopPageClient =
                new FakeShopPageClient();
        private final FakePageDataClient pageDataClient =
                new FakePageDataClient();
        private final MarketplaceListingRepository listingRepository =
                mock(MarketplaceListingRepository.class);
        private final int maxPages;
        private final int maxListings;

        private Fixture(int maxPages, int maxListings) {
            this.maxPages = maxPages;
            this.maxListings = maxListings;
            when(listingRepository
                    .findByMarketplaceAndShopSlugAndProductSlug(
                            eq(Marketplace.YAGA),
                            any(),
                            any()
                    ))
                    .thenReturn(Optional.empty());
        }

        private YagaShopDiscoveryService service() {
            return new YagaShopDiscoveryService(
                    new YagaShopDiscoveryProperties(
                            true,
                            maxPages,
                            maxListings,
                            Duration.ZERO,
                            Duration.ofSeconds(15)
                    ),
                    shopPageClient,
                    pageDataClient,
                    listingRepository,
                    new YagaProductTitleResolver()
            );
        }

        private void shopPage(int page, String... productSlugs) {
            shopPageClient.shopPage(page, null, productSlugs);
        }

        private void next(int page, String nextPageUrl) {
            shopPageClient.next(page, nextPageUrl);
        }

        private void pageData(
                String productSlug,
                YagaImportedProductData data
        ) {
            pageDataClient.pageData(productSlug, data);
        }

        private void failPageData(String productSlug) {
            pageDataClient.failPageData(productSlug);
        }

        private void failShopSource() {
            shopPageClient.failSource();
        }

        private int pageDataCalls(String productSlug) {
            return pageDataClient.calls(productSlug);
        }
    }

    private static class FakeShopPageClient extends YagaShopPageClient {
        private final Map<Integer, YagaShopPage> pages = new HashMap<>();
        private boolean failSource;

        private FakeShopPageClient() {
            super(new tools.jackson.databind.ObjectMapper());
        }

        private void shopPage(
                int page,
                String nextPageUrl,
                String... productSlugs
        ) {
            pages.put(
                    page,
                    new YagaShopPage(
                            "https://www.yaga.ee/nik-ar" +
                                    (page == 1 ? "" : "?page=" + page),
                            List.of(productSlugs)
                                    .stream()
                                    .map(slug ->
                                            new YagaShopPage.ProductLink(
                                                    "nik-ar",
                                                    slug,
                                                    "https://www.yaga.ee/nik-ar/toode/" +
                                                            slug
                                            )
                                    )
                                    .toList(),
                            nextPageUrl
                    )
            );
        }

        private void next(int page, String nextPageUrl) {
            YagaShopPage existing = pages.get(page);
            pages.put(
                    page,
                    new YagaShopPage(
                            existing.pageUrl(),
                            existing.productLinks(),
                            nextPageUrl
                    )
            );
        }

        @Override
        public YagaShopPage getPage(
                String shopSlug,
                int pageNumber,
                Duration requestTimeout
        ) {
            if (failSource) {
                throw new YagaShopListingSourceException(
                        "Yaga shop listing source could not be identified",
                        new YagaShopPageDiagnostics(
                                "https://www.yaga.ee/nik-ar",
                                "https://www.yaga.ee/nik-ar",
                                200,
                                "text/html",
                                5000,
                                "Yaga",
                                true,
                                3000,
                                12,
                                0,
                                List.of(),
                                20,
                                List.of("/_next/static/app.js"),
                                false,
                                false,
                                false,
                                true
                        )
                );
            }
            return pages.getOrDefault(
                    pageNumber,
                    new YagaShopPage(
                            "https://www.yaga.ee/" +
                                    shopSlug +
                                    "?page=" +
                                    pageNumber,
                            List.of(),
                            null
                    )
            );
        }

        private void failSource() {
            this.failSource = true;
        }
    }

    private static class FakePageDataClient extends YagaPageDataClient {
        private final Map<String, YagaImportedProductData> data =
                new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<String, RuntimeException> failures =
                new HashMap<>();

        private FakePageDataClient() {
            super(new YagaPageDataParser(
                    new tools.jackson.databind.ObjectMapper()
            ));
        }

        @Override
        public YagaImportedProductData getProduct(String productUrl) {
            String productSlug =
                    productUrl.substring(productUrl.lastIndexOf('/') + 1);
            calls.merge(productSlug, 1, Integer::sum);
            RuntimeException failure = failures.get(productSlug);
            if (failure != null) {
                throw failure;
            }
            return data.get(productSlug);
        }

        private void pageData(
                String productSlug,
                YagaImportedProductData productData
        ) {
            data.put(productSlug, productData);
        }

        private void failPageData(String productSlug) {
            failures.put(
                    productSlug,
                    new IllegalStateException("detail unavailable")
            );
        }

        private int calls(String productSlug) {
            return calls.getOrDefault(productSlug, 0);
        }
    }
}
