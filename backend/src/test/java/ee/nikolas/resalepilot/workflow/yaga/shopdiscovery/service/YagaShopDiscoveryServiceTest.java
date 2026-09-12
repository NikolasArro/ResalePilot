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
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryStopReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryException;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaProductTitleResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

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
        fixture.shopPage(1, "html-only");
        fixture.apiPage(1, "a", "b", "c");
        fixture.pageData("a", data("published", "nik-ar", "a"));
        fixture.pageData("b", data("published", "nik-ar", "b"));
        fixture.pageData("c", data("published", "nik-ar", "c"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.sourceMode()).isEqualTo("PUBLIC_API");
        assertThat(response.pagesVisited()).isEqualTo(1);
        assertThat(response.cardsDiscovered()).isEqualTo(3);
        assertThat(response.uniqueCandidates()).isEqualTo(3);
        assertThat(response.activeNewCount()).isEqualTo(3);
        assertThat(response.truncated()).isFalse();
        assertThat(response.activeNew())
                .extracting("productSlug")
                .containsExactly("a", "b", "c");
    }

    @Test
    void publicApiCollectsSixtyThreeListingsFromFortyAndTwentyThree() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 40);
        String[] second = slugs(40, 63);
        fixture.shopPage(1, "html-only");
        fixture.apiPage(1, first);
        fixture.apiPage(2, second);
        fixture.apiTotal(1, 41);
        fixture.apiTotal(2, 63);
        addPageData(fixture, first);
        addPageData(fixture, second);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.pagesVisited()).isEqualTo(2);
        assertThat(response.cardsDiscovered()).isEqualTo(63);
        assertThat(response.uniqueCandidates()).isEqualTo(63);
        assertThat(response.activeNewCount()).isEqualTo(63);
        assertThat(response.initialItemCount()).isEqualTo(1);
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
        assertThat(response.truncated()).isFalse();
        assertThat(response.trustedShopIdFound()).isTrue();
        assertThat(response.offsetsRequested()).containsExactly(0, 40);
        assertThat(response.batchSizes()).containsExactly(40, 23);
        assertThat(response.declaredTotal()).isEqualTo(63);
        assertThat(response.declaredTotalSource()).isEqualTo("$.data.total");
        assertThat(response.declaredTotalTrusted()).isTrue();
        assertThat(response.candidateArraySource()).isEqualTo("$.data.list");
        assertThat(response.totalsObserved()).containsExactly(41, 63);
        assertThat(response.minimumObservedTotal()).isEqualTo(41);
        assertThat(response.maximumObservedTotal()).isEqualTo(63);
        assertThat(response.finalObservedTotal()).isEqualTo(63);
        assertThat(response.totalChangedDuringScan()).isTrue();
        assertThat(response.cumulativeRawCount()).isEqualTo(63);
    }

    @Test
    void stableTotalSixtyThreeAlsoConfirmsFortyPlusTwentyThree() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 40);
        String[] second = slugs(40, 63);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiPage(2, second);
        fixture.apiTotal(1, 63);
        fixture.apiTotal(2, 63);
        addPageData(fixture, first);
        addPageData(fixture, second);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.totalsObserved()).containsExactly(63, 63);
        assertThat(response.totalChangedDuringScan()).isFalse();
        assertThat(response.uniqueCandidates()).isEqualTo(63);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void fullApiBatchAdvancesRawOffsetBeforeShortFinalBatch() {
        Fixture fixture = new Fixture(10, 100);
        String[] initial = slugs(0, 40);
        String[] second = slugs(40, 80);
        String[] third = slugs(80, 85);
        fixture.shopPage(1);
        fixture.apiPage(1, initial);
        fixture.apiPage(2, second);
        fixture.apiPage(3, third);
        addPageData(fixture, initial);
        addPageData(fixture, second);
        addPageData(fixture, third);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(85);
        assertThat(response.offsetsRequested()).containsExactly(0, 40, 80);
        assertThat(response.batchSizes()).containsExactly(40, 40, 5);
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void twoFullApiBatchesStillRequireFollowingBatch() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 40);
        String[] second = slugs(40, 80);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiPage(2, second);
        fixture.apiPage(3);
        addPageData(fixture, first);
        addPageData(fixture, second);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(80);
        assertThat(response.offsetsRequested()).containsExactly(0, 40, 80);
        assertThat(response.batchSizes()).containsExactly(40, 40, 0);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void repeatedFullApiPageStopsAsIncomplete() {
        Fixture fixture = new Fixture(10, 100);
        String[] page = slugs(0, 40);
        fixture.shopPage(1);
        fixture.apiPage(1, page);
        fixture.apiPage(2, page);
        addPageData(fixture, page);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 40);
        assertThat(response.pagesVisited()).isEqualTo(2);
        assertThat(response.uniqueCandidates()).isEqualTo(40);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void configuredPageSizeSixtyStillPaginates() {
        Fixture fixture = new Fixture(10, 100, 60);
        String[] first = slugs(0, 60);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiPage(2, "last");
        addPageData(fixture, first);
        fixture.pageData("last", data("published", "nik-ar", "last"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 60);
        assertThat(response.batchSizes()).containsExactly(60, 1);
        assertThat(fixture.shopPageClient.requestedLimits)
                .containsExactly(60, 60);
        assertThat(response.uniqueCandidates()).isEqualTo(61);
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void unknownApiCollectionIsIncomplete() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "html-only");
        fixture.unknownApiPage(1);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isZero();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void totalBelowRawItemsIsDiagnosticAndDoesNotStopFullBatch() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1);
        fixture.apiPage(1, slugs(0, 40));
        fixture.apiPage(2);
        fixture.apiTotal(1, 39);
        fixture.apiTotal(2, 39);
        addPageData(fixture, slugs(0, 40));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 40);
        assertThat(response.maximumObservedTotal()).isEqualTo(40);
        assertThat(response.paginationInconsistencies()).isNotEmpty();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void decreasingTotalDoesNotLowerExpectedBoundary() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 40);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiPage(2, slugs(40, 63));
        fixture.apiTotal(1, 63);
        fixture.apiTotal(2, 41);
        addPageData(fixture, first);
        addPageData(fixture, slugs(40, 63));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 40);
        assertThat(response.totalsObserved()).containsExactly(63, 41);
        assertThat(response.maximumObservedTotal()).isEqualTo(63);
        assertThat(response.finalObservedTotal()).isEqualTo(41);
        assertThat(response.totalChangedDuringScan()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void shortBatchBelowMaximumObservedTotalIsIncomplete() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 20);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiTotal(1, 63);
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.cumulativeRawCount()).isEqualTo(20);
        assertThat(response.maximumObservedTotal()).isEqualTo(63);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
    }

    @Test
    void conflictingDuplicateIdentityIsIncomplete() {
        Fixture fixture = new Fixture(10, 100, 2);
        fixture.shopPage(1);
        fixture.apiMappedBatch(1, mapped(1, "a"), mapped(2, "b"));
        fixture.apiMappedBatch(2, mapped(1, "different-slug"));
        fixture.apiTotal(1, 3);
        fixture.apiTotal(2, 3);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 2);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.paginationInconsistencies())
                .anyMatch(value -> value.contains("conflicting product identity"));
    }

    @Test
    void exactDuplicateAdvancesOffsetButCannotProveCompleteness() {
        Fixture fixture = new Fixture(10, 100, 2);
        fixture.shopPage(1);
        fixture.apiMappedBatch(1, mapped(1, "a"), mapped(1, "a"));
        fixture.apiMappedBatch(2, mapped(2, "b"));
        fixture.apiTotal(1, 3);
        fixture.apiTotal(2, 3);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 2);
        assertThat(response.cumulativeRawCount()).isEqualTo(3);
        assertThat(response.uniqueCandidates()).isEqualTo(2);
        assertThat(response.duplicateIdentities()).containsExactly(
                "1:nik-ar/a"
        );
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
    }

    @Test
    void apiMetadataIsEnrichedFromProductDetail() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "html-only");
        fixture.apiMappedPage(
                1,
                20974812L,
                "mapped",
                Instant.parse("2025-10-09T19:44:38.628Z"),
                2
        );
        fixture.pageData(
                "mapped",
                dataWithExternalId(20974812L, "mapped")
        );

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.activeNew()).singleElement().satisfies(item -> {
            assertThat(item.externalListingId()).isEqualTo("20974812");
            assertThat(item.productSlug()).isEqualTo("mapped");
            assertThat(item.externalCreatedAt()).isEqualTo(
                    Instant.parse("2025-10-09T19:44:38.628Z")
            );
            assertThat(item.imageCount()).isEqualTo(2);
            assertThat(item.publicUrl()).isEqualTo(
                    "https://www.yaga.ee/nik-ar/toode/mapped"
            );
            assertThat(item.title()).isEqualTo("Yaga title mapped");
        });
        assertThat(fixture.pageDataCalls("mapped")).isEqualTo(1);
    }

    @Test
    void structuredTitleTakesPriorityOverDescriptionFallback() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "title-priority");
        fixture.pageData(
                "title-priority",
                dataWithTitleAndDescription(
                        "title-priority",
                        "  Structured   title  ",
                        "Description fallback\nSecond line"
                )
        );

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.activeNew()).singleElement()
                .extracting("title")
                .isEqualTo("Structured title");
    }

    @Test
    void missingDetailTitleAndDescriptionFailsCandidateOnly() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "missing-title", "valid-title");
        fixture.pageData(
                "missing-title",
                dataWithTitleAndDescription("missing-title", null, null)
        );
        fixture.pageData(
                "valid-title",
                data("published", "nik-ar", "valid-title")
        );

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.productSlug()).isEqualTo("missing-title");
            assertThat(failed.reason())
                    .isEqualTo(YagaShopDiscoveryReason.INVALID_DATA);
        });
        assertThat(response.activeNew())
                .extracting("productSlug")
                .containsExactly("valid-title");
        assertThat(response.completenessConfirmed()).isTrue();
    }

    @Test
    void untrustedShopAssociationForbidsApiContinuation() {
        Fixture fixture = new Fixture(10, 100);
        fixture.untrustedShopPage(1, List.of(), "html-only");

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.trustedShopIdFound()).isFalse();
        assertThat(response.offsetsRequested()).isEmpty();
        assertThat(response.uniqueCandidates()).isZero();
        assertThat(response.stopReason()).isEqualTo(
                YagaShopDiscoveryStopReason.PAGINATION_NOT_DISCOVERED
        );
    }

    @Test
    void apiPageSizeMustBeBetweenOneAndOneHundred() {
        assertThatThrownBy(() -> new YagaShopDiscoveryProperties(
                true, 10, 100, 0, Duration.ZERO, Duration.ofSeconds(15)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new YagaShopDiscoveryProperties(
                true, 10, 100, 101, Duration.ZERO, Duration.ofSeconds(15)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPublishedApiBatchConfirmsEndWithoutSoldRequest() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "html-only");
        fixture.apiPage(1);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0);
        assertThat(response.batchSizes()).containsExactly(0);
        assertThat(response.pagesVisited()).isEqualTo(1);
        assertThat(response.uniqueCandidates()).isZero();
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void duplicatesAndFilteringDoNotChangeRawPublishedOffset() {
        Fixture fixture = new Fixture(10, 100);
        String[] initial = IntStream.range(0, 40)
                .mapToObj(index -> index == 39 ? "item-0" : "item-" + index)
                .toArray(String[]::new);
        String[] second = IntStream.range(0, 5)
                .mapToObj(index -> index == 0 ? "item-0" : "api-" + index)
                .toArray(String[]::new);
        fixture.shopPage(1);
        fixture.apiPage(1, initial);
        fixture.apiPage(2, second);
        addPageData(fixture, initial);
        addPageData(fixture, second);
        fixture.pageData(
                "item-1",
                data("sold", "nik-ar", "item-1")
        );

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.offsetsRequested()).containsExactly(0, 40);
        assertThat(response.batchSizes()).containsExactly(40, 5);
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.skipped())
                .extracting("reason")
                .contains(YagaShopDiscoveryReason.SOLD);
    }

    @Test
    void publishedApiFailureDoesNotReturnFalseConfirmedEnd() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a");
        fixture.pageData("a", data("published", "nik-ar", "a"));
        fixture.failApi();

        assertThatThrownBy(() -> fixture.service().discover("nik-ar"))
                .isInstanceOf(YagaShopDiscoveryException.class)
                .hasMessage("Failed to load Yaga published product listings")
                .extracting(exception ->
                        ((YagaShopDiscoveryException) exception)
                                .getDetails().get("stopReason"))
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN.name());
    }

    @Test
    void duplicateBetweenCursorPagesIsNotDuplicated() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 40);
        fixture.shopPage(1);
        fixture.apiPage(1, first);
        fixture.apiPage(2, "item-39");
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.cardsDiscovered()).isEqualTo(41);
        assertThat(response.uniqueCandidates()).isEqualTo(40);
        assertThat(response.activeNewCount()).isEqualTo(40);
        assertThat(response.skipped())
                .extracting("reason")
                .containsExactly(YagaShopDiscoveryReason.DUPLICATE);
        assertThat(fixture.pageDataCalls("item-39")).isEqualTo(1);
    }

    @Test
    void declaredTotalNotReachedContinuesWithPublishedApi() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 32);
        fixture.paginatedShopPage(
                1,
                60,
                null,
                true,
                true,
                false,
                first
        );
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(32);
        assertThat(response.declaredTotal()).isEqualTo(32);
        assertThat(response.offsetsRequested()).containsExactly(0);
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void unrelatedTotalDoesNotConfirmDiscoveryEnd() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 32);
        fixture.untrustedShopPage(
                1,
                List.of("$.props.pageProps.sidebar.results.total=9"),
                first
        );
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isZero();
        assertThat(response.declaredTotal()).isNull();
        assertThat(response.declaredTotalTrusted()).isFalse();
        assertThat(response.rejectedTotalCandidates())
                .contains("$.props.pageProps.sidebar.results.total=9");
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.truncated()).isTrue();
        assertThat(response.stopReason()).isEqualTo(
                YagaShopDiscoveryStopReason.PAGINATION_NOT_DISCOVERED
        );
    }

    @Test
    void trustedApiTotalOverridesHtmlPaginationMetadata() {
        Fixture fixture = new Fixture(10, 100);
        String[] first = slugs(0, 32);
        fixture.paginatedShopPage(
                1, 9, null, false, false, false, first
        );
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(32);
        assertThat(response.declaredTotal()).isEqualTo(32);
        assertThat(response.declaredTotalSource()).isEqualTo("$.data.total");
        assertThat(response.declaredTotalTrusted()).isTrue();
        assertThat(response.rejectedTotalCandidates())
                .doesNotContain("$.data.total=32");
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.offsetsRequested()).containsExactly(0);
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void trustedTotalEqualToUniqueCandidatesConfirmsDiscoveryEnd() {
        Fixture fixture = new Fixture(10, 100);
        String[] items = slugs(0, 32);
        fixture.paginatedShopPage(
                1, 32, null, null, false, true, items
        );
        addPageData(fixture, items);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.declaredTotalTrusted()).isTrue();
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void duplicatesCannotReachTrustedListingTotal() {
        Fixture fixture = new Fixture(1, 100);
        String[] repeated = IntStream.range(0, 40)
                .mapToObj(index -> "same")
                .toArray(String[]::new);
        fixture.paginatedShopPage(
                1, 2, null, false, false, false, repeated
        );
        fixture.pageData("same", data("published", "nik-ar", "same"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.cardsDiscovered()).isEqualTo(40);
        assertThat(response.uniqueCandidates()).isEqualTo(1);
        assertThat(response.completenessConfirmed()).isFalse();
        assertThat(response.stopReason()).isEqualTo(
                YagaShopDiscoveryStopReason.MAX_PAGES
        );
    }

    @Test
    void exactInitialBatchLimitStopsAtMaxListings() {
        Fixture fixture = new Fixture(10, 32);
        String[] first = slugs(0, 40);
        fixture.paginatedShopPage(
                1,
                60,
                null,
                true,
                true,
                false,
                first
        );
        addPageData(fixture, first);

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(32);
        assertThat(response.truncated()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.MAX_LISTINGS);
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
    void conflictingApiIdentityStopsDiscoveryAsIncomplete() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1);
        fixture.apiMappedBatch(
                1,
                mapped(42L, "old-slug"),
                mapped(42L, "new-slug")
        );
        fixture.pageData(
                "old-slug",
                dataWithExternalId(42L, "old-slug")
        );

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(1);
        assertThat(response.activeNew())
                .extracting("externalListingId")
                .containsExactly("42");
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN);
        assertThat(response.completenessConfirmed()).isFalse();
    }

    @Test
    void legacyHtmlNextLinkDoesNotControlPublishedApiOffset() {
        Fixture fixture = new Fixture(10, 100);
        fixture.shopPage(1, "a");
        fixture.next(1, "https://www.yaga.ee/nik-ar?page=2");
        fixture.shopPage(2, "b");
        fixture.next(2, "https://www.yaga.ee/nik-ar");
        fixture.pageData("a", data("published", "nik-ar", "a"));
        fixture.pageData("b", data("published", "nik-ar", "b"));

        YagaShopDiscoveryResponse response =
                fixture.service().discover("nik-ar");

        assertThat(response.pagesVisited()).isEqualTo(1);
        assertThat(response.offsetsRequested()).containsExactly(0);
        assertThat(response.truncated()).isFalse();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
    }

    @Test
    void maxPagesAndMaxListingsBoundDiscovery() {
        Fixture byPages = new Fixture(1, 100);
        String[] fullPage = slugs(0, 40);
        byPages.shopPage(1, fullPage);
        byPages.next(1, "https://www.yaga.ee/nik-ar?page=2");
        addPageData(byPages, fullPage);

        YagaShopDiscoveryResponse pagesResponse =
                byPages.service().discover("nik-ar");
        assertThat(pagesResponse.truncated()).isTrue();
        assertThat(pagesResponse.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.MAX_PAGES);

        Fixture byListings = new Fixture(10, 1);
        byListings.shopPage(1, "a", "b");
        byListings.pageData("a", data("published", "nik-ar", "a"));

        YagaShopDiscoveryResponse response =
                byListings.service().discover("nik-ar");

        assertThat(response.uniqueCandidates()).isEqualTo(1);
        assertThat(response.truncated()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.MAX_LISTINGS);
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
        assertThat(fixture.pageDataCalls("existing-slug")).isZero();
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

        assertThat(response.failed())
                .extracting("reason")
                .containsExactly(
                        YagaShopDiscoveryReason.INVALID_DATA,
                        YagaShopDiscoveryReason.INVALID_DATA
                );
    }

    @Test
    void detailFetchFailureDoesNotAbortWholeDiscovery() {
        Fixture fixture = new Fixture(10, 100);
        fixture.paginatedShopPage(
                1, 2, null, false, false, true, "bad", "good"
        );
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
        assertThat(response.completenessConfirmed()).isTrue();
        assertThat(response.stopReason())
                .isEqualTo(YagaShopDiscoveryStopReason.CONFIRMED_END);
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

    private static String[] slugs(int fromInclusive, int toExclusive) {
        return IntStream.range(fromInclusive, toExclusive)
                .mapToObj(index -> "item-" + index)
                .toArray(String[]::new);
    }

    private static void addPageData(
            Fixture fixture,
            String... productSlugs
    ) {
        for (String productSlug : productSlugs) {
            fixture.pageData(
                    productSlug,
                    data("published", "nik-ar", productSlug)
            );
        }
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
                "Yaga title " + productSlug,
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

    private static YagaShopPage.ProductLink mapped(long id, String slug) {
        return new YagaShopPage.ProductLink(
                "nik-ar",
                slug,
                "https://www.yaga.ee/nik-ar/toode/" + slug,
                id,
                Instant.parse("2025-10-09T19:44:38.628Z"),
                0
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

    private static YagaImportedProductData dataWithExternalId(
            Long externalId,
            String productSlug
    ) {
        YagaImportedProductData base =
                data("published", "nik-ar", productSlug);
        return new YagaImportedProductData(
                externalId,
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
                base.deletedAt()
        );
    }

    private static YagaImportedProductData dataWithTitleAndDescription(
            String productSlug,
            String title,
            String description
    ) {
        YagaImportedProductData base =
                data("published", "nik-ar", productSlug);
        return new YagaImportedProductData(
                base.externalId(),
                base.shopSlug(),
                base.productSlug(),
                title,
                description,
                base.price(),
                base.currency(),
                base.status(),
                base.condition(),
                base.categoryPath(),
                base.images(),
                base.createdAt(),
                base.updatedAt(),
                base.hiddenAt(),
                base.deletedAt()
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
        private final int apiPageSize;

        private Fixture(int maxPages, int maxListings) {
            this(maxPages, maxListings, 40);
        }

        private Fixture(int maxPages, int maxListings, int apiPageSize) {
            this.maxPages = maxPages;
            this.maxListings = maxListings;
            this.apiPageSize = apiPageSize;
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
                            apiPageSize,
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

        private void apiPage(int page, String... productSlugs) {
            shopPageClient.apiBatches.put(
                    page,
                    shopPageClient.links(productSlugs)
            );
        }

        private void apiTotal(int page, int total) {
            shopPageClient.apiTotals.put(page, total);
        }

        private void unknownApiPage(int page) {
            shopPageClient.apiPages.put(
                    page,
                    new YagaShopPage(
                            "https://www.yaga.ee/api/product/",
                            List.of(),
                            null,
                            false,
                            false,
                            false,
                            FakeShopPageClient.SHOP_ID,
                            shopPageClient.diagnostics(
                                    "https://www.yaga.ee/api/product/",
                                    0,
                                    null,
                                    null,
                                    false
                            )
                    )
            );
        }

        private void apiMappedPage(
                int page,
                long externalId,
                String productSlug,
                Instant listedAt,
                int imageCount
        ) {
            shopPageClient.apiBatches.put(
                    page,
                    List.of(new YagaShopPage.ProductLink(
                            "nik-ar",
                            productSlug,
                            "https://www.yaga.ee/nik-ar/toode/" + productSlug,
                            externalId,
                            listedAt,
                            imageCount
                    ))
            );
        }

        private void apiMappedBatch(
                int page,
                YagaShopPage.ProductLink... links
        ) {
            shopPageClient.apiBatches.put(page, List.of(links));
        }

        private void next(int page, String nextPageUrl) {
            shopPageClient.next(page, nextPageUrl);
        }

        private void paginatedShopPage(
                int page,
                Integer declaredTotal,
                String nextPageUrl,
                Boolean hasNextPage,
                boolean nextCursorAvailable,
                boolean completenessConfirmed,
                String... productSlugs
        ) {
            shopPageClient.paginatedShopPage(
                    page,
                    declaredTotal,
                    nextPageUrl,
                    hasNextPage,
                    nextCursorAvailable,
                    completenessConfirmed,
                    productSlugs
            );
        }

        private void untrustedShopPage(
                int page,
                List<String> rejectedTotals,
                String... productSlugs
        ) {
            shopPageClient.untrustedShopPage(
                    page,
                    rejectedTotals,
                    productSlugs
            );
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

        private void failApi() {
            shopPageClient.failApi = true;
        }

        private int pageDataCalls(String productSlug) {
            return pageDataClient.calls(productSlug);
        }
    }

    private static class FakeShopPageClient extends YagaShopPageClient {
        private static final long SHOP_ID = 8413833L;
        private final Map<Integer, YagaShopPage> pages = new HashMap<>();
        private final Map<Integer, YagaShopPage> apiPages = new HashMap<>();
        private final Map<Integer, List<YagaShopPage.ProductLink>> apiBatches =
                new HashMap<>();
        private final Map<Integer, Integer> apiTotals = new HashMap<>();
        private final List<Integer> requestedOffsets = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private boolean failSource;
        private boolean failApi;
        private int apiCalls;

        private FakeShopPageClient() {
            super(new tools.jackson.databind.ObjectMapper());
        }

        private void shopPage(
                int page,
                String nextPageUrl,
                String... productSlugs
        ) {
            List<YagaShopPage.ProductLink> links = links(productSlugs);
            String pageUrl = "https://www.yaga.ee/nik-ar" +
                    (page == 1 ? "" : "?page=" + page);
            pages.put(
                    page,
                    new YagaShopPage(
                            pageUrl,
                            links,
                            nextPageUrl,
                            false,
                            true,
                            false,
                            SHOP_ID,
                            diagnostics(pageUrl, links.size(), null, null, false)
                    )
            );
            if (page == 1) {
                apiBatches.put(1, links);
            }
        }

        private void next(int page, String nextPageUrl) {
            YagaShopPage existing = pages.get(page);
            pages.put(
                    page,
                    new YagaShopPage(
                            existing.pageUrl(),
                            existing.productLinks(),
                            nextPageUrl,
                            false,
                            true,
                            false,
                            SHOP_ID,
                            existing.diagnostics()
                    )
            );
        }

        private void paginatedShopPage(
                int page,
                Integer declaredTotal,
                String nextPageUrl,
                Boolean hasNextPage,
                boolean nextCursorAvailable,
                boolean completenessConfirmed,
                String... productSlugs
        ) {
            List<YagaShopPage.ProductLink> links = links(productSlugs);
            String pageUrl = "https://www.yaga.ee/nik-ar?batch=" + page;
            String nextRequestPath = nextPageUrl == null
                    ? null
                    : "/api/products?cursor=<redacted>";
            YagaShopPageDiagnostics diagnostics =
                    new YagaShopPageDiagnostics(
                            pageUrl,
                            pageUrl,
                            200,
                            "application/json",
                            5000,
                            "Yaga",
                            true,
                            3000,
                            0,
                            0,
                            List.of(),
                            1,
                            List.of("/_next/static/shop.js"),
                            false,
                            false,
                            false,
                            true,
                            links.size(),
                            declaredTotal,
                            declaredTotal == null
                                    ? null
                                    : "$.products.totalCount",
                            declaredTotal != null,
                            List.of(),
                            List.of(
                                    "$.products.totalCount=" + declaredTotal,
                                    "$.products.pageInfo.hasNextPage=" +
                                            hasNextPage
                            ),
                            hasNextPage,
                            hasNextPage == null
                                    ? null
                                    : "$.products.pageInfo.hasNextPage",
                            nextCursorAvailable,
                            nextRequestPath,
                            nextPageUrl == null
                                    ? null
                                    : "$.products.pageInfo.nextUrl",
                            "$.products.items",
                            List.of("/api/products"),
                            true,
                            "$.shop.id",
                            completenessConfirmed
                    );
            pages.put(
                    page,
                    new YagaShopPage(
                            pageUrl,
                            links,
                            nextPageUrl,
                            completenessConfirmed,
                            true,
                            false,
                            SHOP_ID,
                            diagnostics
                    )
            );
            apiBatches.put(page, links);
        }

        private void untrustedShopPage(
                int page,
                List<String> rejectedTotals,
                String... productSlugs
        ) {
            List<YagaShopPage.ProductLink> links = links(productSlugs);
            String pageUrl = "https://www.yaga.ee/nik-ar?batch=" + page;
            pages.put(
                    page,
                    new YagaShopPage(
                            pageUrl,
                            links,
                            null,
                            false,
                            true,
                            false,
                            new YagaShopPageDiagnostics(
                                    pageUrl, pageUrl, 200,
                                    "application/json", 5000, "Yaga",
                                    true, 3000, 0, 0, List.of(), 1,
                                    List.of(), false, false, false, true,
                                    links.size(), null, null, false,
                                    rejectedTotals, List.of(), null, null,
                                    false, null, null,
                                    "$.props.pageProps.products.items",
                                    List.of(), false
                            )
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
                                true,
                                0,
                                null,
                                null,
                                false,
                                List.of(),
                                List.of(),
                                null,
                                null,
                                false,
                                null,
                                null,
                                null,
                                List.of("/_next/static/app.js"),
                                false
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

        @Override
        public YagaShopPage getContinuationPage(
                String shopSlug,
                int pageNumber,
                String nextPageUrl,
                Duration requestTimeout
        ) {
            return getPage(shopSlug, pageNumber, requestTimeout);
        }

        @Override
        public YagaShopPage getPublishedProductsPage(
                String shopSlug,
                long trustedShopId,
                int offset,
                int limit,
                Duration requestTimeout
        ) {
            requestedOffsets.add(offset);
            requestedLimits.add(limit);
            if (failApi) {
                throw new IllegalStateException("API unavailable");
            }
            apiCalls++;
            if (apiPages.containsKey(apiCalls)) {
                return apiPages.get(apiCalls);
            }
            List<YagaShopPage.ProductLink> links = apiBatches.get(apiCalls);
            if (links == null) {
                return emptyApiPage(offset);
            }
            int total = apiTotals.getOrDefault(
                    apiCalls,
                    inferredApiTotal(limit)
            );
            return apiPage(links, offset, total, limit);
        }

        private int inferredApiTotal(int limit) {
            int lastPage = apiBatches.keySet().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(1);
            int lastSize = apiBatches.getOrDefault(lastPage, List.of()).size();
            return (lastPage - 1) * limit + lastSize +
                    (lastSize == limit ? limit : 0);
        }

        private YagaShopPage apiPage(
                List<YagaShopPage.ProductLink> links,
                int offset,
                int total,
                int limit
        ) {
            String pageUrl = "https://www.yaga.ee/api/product/";
            return new YagaShopPage(
                    pageUrl,
                    links,
                    null,
                    links.size() < limit,
                    true,
                    links.isEmpty(),
                    SHOP_ID,
                    diagnostics(
                            pageUrl,
                            links.size(),
                            total,
                            null,
                            links.size() < limit
                    )
            );
        }

        private List<YagaShopPage.ProductLink> links(
                String... productSlugs
        ) {
            return List.of(productSlugs)
                    .stream()
                    .map(slug -> new YagaShopPage.ProductLink(
                            "nik-ar",
                            slug,
                            "https://www.yaga.ee/nik-ar/toode/" + slug,
                            (long) Math.abs(slug.hashCode()),
                            Instant.parse("2026-01-01T00:00:00Z"),
                            1
                    ))
                    .toList();
        }

        private YagaShopPageDiagnostics diagnostics(
                String pageUrl,
                int rawSize,
                Integer total,
                Boolean hasNext,
                boolean completenessConfirmed
        ) {
            return new YagaShopPageDiagnostics(
                    pageUrl, pageUrl, 200, "application/json", 5000,
                    "Yaga", true, 3000, 0, 0, List.of(), 1,
                    List.of(), false, false, false, true, rawSize,
                    total,
                    total == null ? null : "$.data.total",
                    total != null, List.of(), List.of(), hasNext,
                    hasNext == null ? null : "$.products.pageInfo.hasNextPage",
                    false, null, "confirmed:/api/product/", "$.data.list",
                    List.of("/api/product/"), true, "$.shop.id",
                    completenessConfirmed
            );
        }

        private YagaShopPage emptyApiPage(int offset) {
            String pageUrl = "https://www.yaga.ee/api/product/";
            return new YagaShopPage(
                    pageUrl,
                    List.of(),
                    null,
                    true,
                    true,
                    true,
                    SHOP_ID,
                    diagnostics(pageUrl, 0, offset, null, true)
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
