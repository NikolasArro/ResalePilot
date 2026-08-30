package ee.nikolas.resalepilot.exception;

public class YagaImportException extends RuntimeException {

    public YagaImportException(String message) {
        super(message);
    }

    public YagaImportException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}