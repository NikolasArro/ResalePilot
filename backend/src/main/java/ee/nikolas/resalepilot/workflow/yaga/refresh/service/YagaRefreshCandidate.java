package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import java.time.Instant;

public record YagaRefreshCandidate(
        Long listingId,
        Long productId,
        String sku,
        String title,
        String externalListingId,
        String shopSlug,
        String productSlug,
        String externalUrl,
        Instant externalCreatedAt,
        Instant listingCreatedAt,
        Instant orderingTimestamp,
        int productImageCount,
        int listingImageCount
) {
}
