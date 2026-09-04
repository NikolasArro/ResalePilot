package ee.nikolas.resalepilot.service;

public record YagaHidingDraftData(
        Long oldListingId,
        Long newListingId,
        Long productId,
        String shopSlug,
        String oldExternalListingId,
        String oldProductSlug,
        String oldExternalUrl,
        String newExternalListingId,
        String newProductSlug,
        String newExternalUrl
) {
}
