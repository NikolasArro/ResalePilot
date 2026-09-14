package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.util.UUID;

public record YagaRefreshOperatorJobResponse(
        UUID jobId,
        int selectionOrder,
        Long productId,
        String productTitle,
        Long oldListingId,
        String oldProductUrl,
        Long newListingId,
        String newProductUrl,
        YagaRefreshJobStatus jobStatus,
        String publicationStatus,
        YagaRefreshHideStatus hideStatus
) {
}
