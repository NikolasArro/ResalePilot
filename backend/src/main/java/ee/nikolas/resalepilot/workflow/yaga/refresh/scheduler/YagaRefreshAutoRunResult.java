package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;

import java.util.UUID;

public record YagaRefreshAutoRunResult(
        UUID runId,
        boolean started,
        boolean completed,
        YagaRefreshRunStatus status,
        String safeMessage
) {
    public static YagaRefreshAutoRunResult skipped(String safeMessage) {
        return new YagaRefreshAutoRunResult(
                null,
                false,
                false,
                null,
                safeMessage
        );
    }
}
