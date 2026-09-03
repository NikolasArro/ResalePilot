package ee.nikolas.resalepilot.dto;

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
