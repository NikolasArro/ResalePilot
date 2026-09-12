package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

public class YagaRefreshHidingAuthException extends RuntimeException {

    public YagaRefreshHidingAuthException() {
        super("Yaga hiding authentication is unavailable");
    }
}
