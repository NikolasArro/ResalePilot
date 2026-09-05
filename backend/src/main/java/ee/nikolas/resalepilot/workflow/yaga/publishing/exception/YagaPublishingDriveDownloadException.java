package ee.nikolas.resalepilot.workflow.yaga.publishing.exception;

public class YagaPublishingDriveDownloadException
        extends RuntimeException {

    public YagaPublishingDriveDownloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
