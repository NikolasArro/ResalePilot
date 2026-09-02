package ee.nikolas.resalepilot.exception;

import java.util.UUID;

public class YagaPublicationPreparationNotFoundException
        extends RuntimeException {

    public YagaPublicationPreparationNotFoundException(UUID id) {
        super("Yaga publication preparation not found: " + id);
    }
}
