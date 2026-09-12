package ee.nikolas.resalepilot.workflow.yaga.refresh.exception;

import java.util.UUID;

public class YagaRefreshJobNotFoundException extends RuntimeException {

    public YagaRefreshJobNotFoundException(UUID jobId) {
        super("Yaga refresh job not found: " + jobId);
    }
}
