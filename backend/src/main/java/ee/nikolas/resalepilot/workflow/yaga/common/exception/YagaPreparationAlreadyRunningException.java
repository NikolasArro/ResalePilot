package ee.nikolas.resalepilot.workflow.yaga.common.exception;

public class YagaPreparationAlreadyRunningException
        extends RuntimeException {

    public YagaPreparationAlreadyRunningException() {
        super("Yaga form preparation is already running");
    }
}
