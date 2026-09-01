package ee.nikolas.resalepilot.exception;

public class YagaPreparationAlreadyRunningException
        extends RuntimeException {

    public YagaPreparationAlreadyRunningException() {
        super("Yaga form preparation is already running");
    }
}
