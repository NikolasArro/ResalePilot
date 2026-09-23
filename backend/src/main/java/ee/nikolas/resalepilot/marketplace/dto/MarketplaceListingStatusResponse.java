package ee.nikolas.resalepilot.marketplace.dto;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;

public record MarketplaceListingStatusResponse(
        Long accountId,
        Long listingId,
        String externalListingId,
        String productSlug,
        MarketplaceListingStatus status,
        boolean current
) {
}
