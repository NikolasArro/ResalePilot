package ee.nikolas.resalepilot.marketplace.service;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.product.entity.Product;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class YagaListingLikeObservationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-26T12:00:00Z");

    private final YagaListingLikeObservationService service =
            new YagaListingLikeObservationService(
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void firstObservationPersistsCurrentLikeCountOnly() {
        MarketplaceListing listing = listing();

        service.observe(listing, 3);

        assertThat(listing.getLikeCount()).isEqualTo(3);
        assertThat(listing.getLikeCountObservedAt()).isEqualTo(NOW);
        assertThat(listing.getLastLikeIncreaseObservedAt()).isNull();
    }

    @Test
    void countIncreaseUpdatesBothObservationTimestamps() {
        MarketplaceListing listing = listing(3, NOW.minusSeconds(60), null);

        service.observe(listing, 4);

        assertThat(listing.getLikeCount()).isEqualTo(4);
        assertThat(listing.getLikeCountObservedAt()).isEqualTo(NOW);
        assertThat(listing.getLastLikeIncreaseObservedAt()).isEqualTo(NOW);
    }

    @Test
    void unchangedCountPreservesTimestamps() {
        Instant observedAt = NOW.minusSeconds(60);
        Instant increasedAt = NOW.minusSeconds(30);
        MarketplaceListing listing = listing(4, observedAt, increasedAt);

        service.observe(listing, 4);

        assertThat(listing.getLikeCount()).isEqualTo(4);
        assertThat(listing.getLikeCountObservedAt()).isEqualTo(observedAt);
        assertThat(listing.getLastLikeIncreaseObservedAt())
                .isEqualTo(increasedAt);
    }

    @Test
    void decreaseUpdatesCountAndObservationButPreservesLastIncrease() {
        Instant increasedAt = NOW.minusSeconds(30);
        MarketplaceListing listing =
                listing(4, NOW.minusSeconds(60), increasedAt);

        service.observe(listing, 3);

        assertThat(listing.getLikeCount()).isEqualTo(3);
        assertThat(listing.getLikeCountObservedAt()).isEqualTo(NOW);
        assertThat(listing.getLastLikeIncreaseObservedAt())
                .isEqualTo(increasedAt);
    }

    @Test
    void zeroLikeObservationIsEligibleImmediately() {
        MarketplaceListing listing = listing();

        service.observe(listing, 0);

        assertThat(service.isRefreshEligible(listing)).isTrue();
    }

    @Test
    void positiveRecentObservedIncreaseIsNotEligible() {
        assertThat(YagaListingLikeObservationService.isRefreshEligible(
                1,
                NOW.minusSeconds(1),
                NOW.minusSeconds(1),
                NOW
        )).isFalse();
    }

    @Test
    void positiveOldObservedIncreaseIsEligible() {
        assertThat(YagaListingLikeObservationService.isRefreshEligible(
                1,
                NOW.minusSeconds(1),
                NOW.minusSeconds(5 * 24 * 60 * 60 + 1),
                NOW
        )).isTrue();
    }

    @Test
    void positiveUnchangedLikesNeedOlderThanFiveDays() {
        assertThat(YagaListingLikeObservationService.isRefreshEligible(
                1,
                NOW.minusSeconds(5 * 24 * 60 * 60),
                null,
                NOW
        )).isFalse();

        assertThat(YagaListingLikeObservationService.isRefreshEligible(
                1,
                NOW.minusSeconds(5 * 24 * 60 * 60 + 1),
                null,
                NOW
        )).isTrue();
    }

    private MarketplaceListing listing(
            int likeCount,
            Instant likeCountObservedAt,
            Instant lastLikeIncreaseObservedAt
    ) {
        MarketplaceListing listing = listing();
        listing.setLikeCount(likeCount);
        listing.setLikeCountObservedAt(likeCountObservedAt);
        listing.setLastLikeIncreaseObservedAt(lastLikeIncreaseObservedAt);
        return listing;
    }

    private MarketplaceListing listing() {
        return new MarketplaceListing(
                new Product("SKU", "Title"),
                Marketplace.YAGA,
                "external-1",
                "https://www.yaga.ee/nik-ar/toode/slug"
        );
    }
}
