package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

public class YagaRefreshHideFinalizationException extends RuntimeException {

    public YagaRefreshHideFinalizationException() {
        super("Yaga hide was confirmed, but local finalization requires reconciliation");
    }
}
