package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;

import java.util.UUID;

public record YagaOnDemandRefreshResponse(
        UUID runId,
        YagaRefreshRunStatus status,
        boolean completed,
        Integer selectedJobCount,
        String safeMessage
) {
}
