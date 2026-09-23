package ee.nikolas.resalepilot.marketplace.dto;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import jakarta.validation.constraints.NotNull;

public record MarketplaceListingStatusUpdateRequest(
        @NotNull
        MarketplaceListingStatus status
) {
}
