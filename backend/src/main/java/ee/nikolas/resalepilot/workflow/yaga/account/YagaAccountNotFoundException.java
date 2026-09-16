package ee.nikolas.resalepilot.workflow.yaga.account;

public class YagaAccountNotFoundException extends RuntimeException {

    public YagaAccountNotFoundException(Long accountId) {
        super("Yaga account was not found: " + accountId);
    }
}
