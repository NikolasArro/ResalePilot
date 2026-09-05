package ee.nikolas.resalepilot.integration.drive.exception;

public class GoogleDriveAccessException
        extends RuntimeException {

    public GoogleDriveAccessException(String message) {
        super(message);
    }

    public GoogleDriveAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
