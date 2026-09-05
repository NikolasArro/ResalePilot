package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

import java.util.UUID;

public class YagaRefreshRunNotFoundException extends RuntimeException {

    public YagaRefreshRunNotFoundException(UUID runId) {
        super("Yaga refresh run not found: " + runId);
    }
}
