package ee.nikolas.resalepilot.workflow.yaga.common.exception;

public class YagaPublicationForbiddenException extends RuntimeException {

    public YagaPublicationForbiddenException() {
        super("Yaga publication confirmation was rejected");
    }
}
