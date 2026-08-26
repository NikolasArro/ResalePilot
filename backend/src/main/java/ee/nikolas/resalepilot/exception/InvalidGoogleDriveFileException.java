package ee.nikolas.resalepilot.exception;

public class InvalidGoogleDriveFileException
        extends RuntimeException {

    public InvalidGoogleDriveFileException(
            String fileId,
            String mimeType
    ) {
        super(
                "Google Drive file " + fileId +
                        " is not a supported image: " + mimeType
        );
    }
}