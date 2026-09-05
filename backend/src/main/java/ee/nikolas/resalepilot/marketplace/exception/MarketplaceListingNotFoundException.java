package ee.nikolas.resalepilot.marketplace.exception;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;

public class MarketplaceListingNotFoundException
        extends RuntimeException {

    public MarketplaceListingNotFoundException(Long listingId) {
        super("Marketplace listing not found with id: " + listingId);
    }
}
