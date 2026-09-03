package ee.nikolas.resalepilot.service;

public record YagaPublishedUrl(
        String originalUrl,
        String publicUrl,
        String shopSlug,
        String productSlug,
        boolean publicProductUrl
) {
}
