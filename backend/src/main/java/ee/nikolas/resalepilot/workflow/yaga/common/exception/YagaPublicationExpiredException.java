package ee.nikolas.resalepilot.workflow.yaga.common.exception;

public class YagaPublicationExpiredException extends RuntimeException {

    public YagaPublicationExpiredException() {
        super("Yaga publication preparation has expired");
    }
}
