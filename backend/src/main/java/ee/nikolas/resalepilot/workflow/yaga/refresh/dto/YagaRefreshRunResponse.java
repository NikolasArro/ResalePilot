package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record YagaRefreshRunResponse(
        UUID runId,
        YagaRefreshTriggerType triggerType,
        YagaRefreshRunMode mode,
        YagaRefreshRunStatus status,
        int requestedBatchSize,
        int selectedJobCount,
        Instant createdAt,
        Instant completedAt,
        List<YagaRefreshJobResponse> candidates
) {
}
