package ee.nikolas.resalepilot.exception;

public class YagaPublishingDriveDownloadException
        extends RuntimeException {

    public YagaPublishingDriveDownloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
