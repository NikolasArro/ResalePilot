package ee.nikolas.resalepilot.workflow.yaga.shopimport.dto;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItemStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaShopImportItemResponse(
        UUID itemId,
        int selectionOrder,
        String externalListingId,
        String productSlug,
        String title,
        String publicUrl,
        Instant externalCreatedAt,
        int expectedImageCount,
        YagaShopImportItemStatus status,
        Long productId,
        Long marketplaceListingId,
        String lastErrorCode,
        String lastSafeErrorMessage
) {
}
