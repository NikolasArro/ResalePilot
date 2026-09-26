package ee.nikolas.resalepilot.marketplace.service;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class YagaListingLikeObservationService {

    static final Duration RECENT_LIKE_WINDOW = Duration.ofDays(5);

    private final Clock clock;

    public YagaListingLikeObservationService(Clock clock) {
        this.clock = clock;
    }

    public void observe(
            MarketplaceListing listing,
            Integer newLikeCount
    ) {
        if (newLikeCount == null) {
            return;
        }
        if (newLikeCount < 0) {
            throw new IllegalArgumentException(
                    "Yaga like count cannot be negative"
            );
        }

        Instant now = clock.instant();
        Integer previousLikeCount = listing.getLikeCount();
        if (previousLikeCount == null) {
            listing.setLikeCount(newLikeCount);
            listing.setLikeCountObservedAt(now);
            return;
        }
        if (newLikeCount > previousLikeCount) {
            listing.setLikeCount(newLikeCount);
            listing.setLikeCountObservedAt(now);
            listing.setLastLikeIncreaseObservedAt(now);
            return;
        }
        if (newLikeCount < previousLikeCount) {
            listing.setLikeCount(newLikeCount);
            listing.setLikeCountObservedAt(now);
        }
    }

    public boolean isRefreshEligible(MarketplaceListing listing) {
        return isRefreshEligible(
                listing.getLikeCount(),
                listing.getLikeCountObservedAt(),
                listing.getLastLikeIncreaseObservedAt(),
                clock.instant()
        );
    }

    public static boolean isRefreshEligible(
            Integer likeCount,
            Instant likeCountObservedAt,
            Instant lastLikeIncreaseObservedAt,
            Instant now
    ) {
        if (likeCount == null) {
            return false;
        }
        if (likeCount == 0) {
            return true;
        }
        Instant cutoff = now.minus(RECENT_LIKE_WINDOW);
        if (lastLikeIncreaseObservedAt != null) {
            return lastLikeIncreaseObservedAt.isBefore(cutoff);
        }
        return likeCountObservedAt != null &&
                likeCountObservedAt.isBefore(cutoff);
    }
}
