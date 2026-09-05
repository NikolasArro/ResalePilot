package ee.nikolas.resalepilot.integration.drive.exception;

public class GoogleDriveFileNotFoundException
        extends RuntimeException {

    public GoogleDriveFileNotFoundException(String fileId) {
        super("Google Drive file not found: " + fileId);
    }
}