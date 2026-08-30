package ee.nikolas.resalepilot.exception;

public class MarketplaceListingNotFoundException
        extends RuntimeException {

    public MarketplaceListingNotFoundException(Long listingId) {
        super("Marketplace listing not found with id: " + listingId);
    }
}
