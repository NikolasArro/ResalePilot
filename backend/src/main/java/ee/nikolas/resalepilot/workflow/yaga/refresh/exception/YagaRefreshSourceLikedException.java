package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

/** Positive current likes, verified before any publication preparation. */
public class YagaRefreshSourceLikedException extends YagaRefreshInvalidStateException {
    public YagaRefreshSourceLikedException() {
        super("SOURCE_HAS_LIKES: source listing now has likes");
    }
}
