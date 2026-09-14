package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;

import java.util.UUID;

public record YagaRefreshOperatorStateResponse(
        UUID runId,
        YagaRefreshRunStatus runStatus,
        int selectedJobCount,
        int completedJobCount,
        int remainingJobCount,
        YagaRefreshOperatorJobResponse currentJob,
        YagaRefreshOperatorNextAction nextAction,
        boolean requiresManualConfirmation,
        String blockedReason
) {
}
