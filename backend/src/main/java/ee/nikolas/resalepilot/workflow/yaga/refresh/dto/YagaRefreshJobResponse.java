package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshJobResponse(
        UUID jobId,
        int selectionOrder,
        Long productId,
        String sku,
        String productTitle,
        Long oldListingId,
        String oldExternalListingId,
        String oldShopSlug,
        String oldProductSlug,
        String oldProductUrl,
        Instant selectedExternalCreatedAt,
        Instant selectedListingCreatedAt,
        Instant orderingTimestamp,
        Integer expectedProductImageCount,
        Integer expectedMarketplaceListingImageCount,
        YagaRefreshJobStatus status
) {
}
