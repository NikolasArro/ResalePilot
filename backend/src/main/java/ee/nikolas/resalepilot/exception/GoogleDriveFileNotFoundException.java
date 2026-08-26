package ee.nikolas.resalepilot.exception;

public class GoogleDriveFileNotFoundException
        extends RuntimeException {

    public GoogleDriveFileNotFoundException(String fileId) {
        super("Google Drive file not found: " + fileId);
    }
}