package ee.nikolas.resalepilot.marketplace.exception;

public class YagaListingReconciliationIncompleteException
        extends RuntimeException {

    public YagaListingReconciliationIncompleteException(String shopSlug) {
        super("Yaga listing reconciliation is incomplete for shop: " + shopSlug);
    }
}
