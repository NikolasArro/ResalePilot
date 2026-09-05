package ee.nikolas.resalepilot.workflow.yaga.common.exception;

public class YagaPublicationConfirmDisabledException
        extends RuntimeException {

    public YagaPublicationConfirmDisabledException() {
        super("Yaga publication confirmation is disabled");
    }
}
