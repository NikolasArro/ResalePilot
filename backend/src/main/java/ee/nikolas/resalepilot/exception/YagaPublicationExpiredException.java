package ee.nikolas.resalepilot.exception;

public class YagaPublicationExpiredException extends RuntimeException {

    public YagaPublicationExpiredException() {
        super("Yaga publication preparation has expired");
    }
}
