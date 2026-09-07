package ee.nikolas.resalepilot.integration.drive.exception;

public class GoogleDriveAuthException
        extends RuntimeException {

    public GoogleDriveAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
