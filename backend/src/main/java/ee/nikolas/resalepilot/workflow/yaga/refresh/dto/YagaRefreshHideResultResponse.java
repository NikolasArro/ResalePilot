package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshHideResultResponse(
        UUID runId,
        UUID jobId,
        YagaRefreshRunStatus runStatus,
        YagaRefreshJobStatus jobStatus,
        UUID hidePreparationId,
        YagaRefreshHideStatus hideStatus,
        Long oldListingId,
        Long newListingId,
        Instant hideConfirmedAt,
        String lastErrorCode,
        String lastSafeErrorMessage
) {
}
