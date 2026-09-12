package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;

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
        UUID publicationPreparationId,
        String publicationStatus,
        Long newListingId,
        String newExternalListingId,
        String newShopSlug,
        String newProductSlug,
        String newProductUrl,
        Instant publicationPreparedAt,
        Instant publicationConfirmStartedAt,
        Instant publicationConfirmedAt,
        UUID hidePreparationId,
        YagaRefreshHideStatus hideStatus,
        Instant hidePreparedAt,
        Instant hideConfirmStartedAt,
        Instant hideConfirmedAt,
        String lastErrorCode,
        String lastSafeErrorMessage,
        YagaRefreshJobStatus status
) {
}
