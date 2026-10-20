package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

/** An identity-verified source with explicit evidence that it is inactive. */
public class YagaRefreshSourceInactiveException extends YagaRefreshInvalidStateException {
    public YagaRefreshSourceInactiveException() {
        super("SOURCE_NOT_ACTIVE: source listing is confirmed inactive");
    }
}
