package ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;

public record YagaListingPublicationReconcileResponse(
        Long oldListingId,
        Long newListingId,
        Long productId,
        boolean published,
        String externalListingId,
        String newProductUrl,
        String newShopSlug,
        String newProductSlug,
        int imageCount,
        YagaPublicationStatus status
) {
}
