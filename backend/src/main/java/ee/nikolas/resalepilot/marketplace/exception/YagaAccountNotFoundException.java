package ee.nikolas.resalepilot.marketplace.exception;

public class YagaAccountNotFoundException
        extends RuntimeException {

    public YagaAccountNotFoundException(Long accountId) {
        super("Yaga account not found with id: " + accountId);
    }
}
