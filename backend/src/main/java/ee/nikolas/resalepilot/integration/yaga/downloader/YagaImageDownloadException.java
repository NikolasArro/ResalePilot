package ee.nikolas.resalepilot.integration.yaga.downloader;

public class YagaImageDownloadException
        extends RuntimeException {

    public YagaImageDownloadException(String message) {
        super(message);
    }

    public YagaImageDownloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
