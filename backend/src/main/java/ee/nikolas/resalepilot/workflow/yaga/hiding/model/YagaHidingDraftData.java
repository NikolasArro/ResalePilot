package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

public record YagaHidingDraftData(
        Long oldListingId,
        Long newListingId,
        Long yagaAccountId,
        Long productId,
        String shopSlug,
        String oldExternalListingId,
        String oldProductSlug,
        String oldExternalUrl,
        String newExternalListingId,
        String newProductSlug,
        String newExternalUrl
) {
    public YagaHidingDraftData(
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
        this(
                oldListingId,
                newListingId,
                1L,
                productId,
                shopSlug,
                oldExternalListingId,
                oldProductSlug,
                oldExternalUrl,
                newExternalListingId,
                newProductSlug,
                newExternalUrl
        );
    }
}
