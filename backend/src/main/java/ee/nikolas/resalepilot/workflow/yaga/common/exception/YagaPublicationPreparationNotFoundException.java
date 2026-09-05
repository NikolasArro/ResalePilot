package ee.nikolas.resalepilot.workflow.yaga.common.exception;

import java.util.UUID;

public class YagaPublicationPreparationNotFoundException
        extends RuntimeException {

    public YagaPublicationPreparationNotFoundException(UUID id) {
        super("Yaga publication preparation not found: " + id);
    }
}
