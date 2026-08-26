package ee.nikolas.resalepilot.exception;

public class GoogleDriveAccessException
        extends RuntimeException {

    public GoogleDriveAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}