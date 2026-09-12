package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshPublicationPreparationResponse(
        UUID runId,
        UUID jobId,
        Long productId,
        Long oldListingId,
        YagaRefreshJobStatus jobStatus,
        UUID publicationPreparationId,
        String publicationStatus,
        String confirmationToken,
        Instant expiresAt,
        YagaPublishReadinessResponse readiness
) {
}
