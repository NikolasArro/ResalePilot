package ee.nikolas.resalepilot.workflow.yaga.reconciliation.model;

public record YagaPublishedUrl(
        String originalUrl,
        String publicUrl,
        String shopSlug,
        String productSlug,
        boolean publicProductUrl
) {
}
