package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshPublicationResultResponse(
        UUID runId,
        UUID jobId,
        YagaRefreshRunStatus runStatus,
        YagaRefreshJobStatus jobStatus,
        UUID publicationPreparationId,
        Long oldListingId,
        Long newListingId,
        String newExternalListingId,
        String newShopSlug,
        String newProductSlug,
        String newProductUrl,
        Instant publicationConfirmedAt,
        String lastErrorCode,
        String lastSafeErrorMessage
) {
}
