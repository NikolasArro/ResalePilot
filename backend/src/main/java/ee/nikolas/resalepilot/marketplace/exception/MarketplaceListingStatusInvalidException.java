package ee.nikolas.resalepilot.marketplace.exception;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;

public class MarketplaceListingStatusInvalidException
        extends RuntimeException {

    public MarketplaceListingStatusInvalidException(
            MarketplaceListingStatus status
    ) {
        super("Marketplace listing status cannot be set locally: " + status);
    }
}
