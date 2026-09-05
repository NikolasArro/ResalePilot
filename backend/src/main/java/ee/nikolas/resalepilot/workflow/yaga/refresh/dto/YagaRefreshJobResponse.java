package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshJobResponse(
        UUID jobId,
        int selectionOrder,
        Long productId,
        String sku,
        Long oldListingId,
        String oldProductUrl,
        Instant orderingTimestamp,
        YagaRefreshJobStatus status
) {
}
