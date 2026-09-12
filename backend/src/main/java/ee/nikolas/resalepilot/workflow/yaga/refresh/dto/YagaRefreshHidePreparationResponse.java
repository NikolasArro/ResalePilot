package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshHidePreparationResponse(
        UUID runId,
        UUID jobId,
        Long productId,
        Long oldListingId,
        Long newListingId,
        YagaRefreshJobStatus jobStatus,
        UUID hidePreparationId,
        YagaRefreshHideStatus hideStatus,
        String confirmationToken,
        Instant expiresAt,
        YagaRefreshHideReadinessResponse readiness
) {
}
