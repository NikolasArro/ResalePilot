package ee.nikolas.resalepilot.exception;

public class YagaPublicationConfirmDisabledException
        extends RuntimeException {

    public YagaPublicationConfirmDisabledException() {
        super("Yaga publication confirmation is disabled");
    }
}
