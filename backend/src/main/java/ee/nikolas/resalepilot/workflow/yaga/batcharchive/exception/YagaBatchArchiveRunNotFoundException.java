package ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception;

import java.util.UUID;

public class YagaBatchArchiveRunNotFoundException
        extends RuntimeException {

    public YagaBatchArchiveRunNotFoundException(UUID runId) {
        super("Yaga batch archive run not found: " + runId);
    }
}
