package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import java.time.Instant;

public record YagaRefreshCandidate(
        Long listingId,
        Long productId,
        String sku,
        String shopSlug,
        String productSlug,
        String externalUrl,
        Instant orderingTimestamp
) {
}
