package ee.nikolas.resalepilot.exception;

public class YagaPublicationForbiddenException extends RuntimeException {

    public YagaPublicationForbiddenException() {
        super("Yaga publication confirmation was rejected");
    }
}
