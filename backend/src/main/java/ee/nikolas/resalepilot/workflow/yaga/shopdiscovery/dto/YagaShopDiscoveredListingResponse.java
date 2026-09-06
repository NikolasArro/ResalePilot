package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto;

import java.time.Instant;

public record YagaShopDiscoveredListingResponse(
        String externalListingId,
        String productSlug,
        String title,
        String publicUrl,
        Instant externalCreatedAt,
        int imageCount
) {
    public YagaShopDiscoveredListingResponse(
            String externalListingId,
            String productSlug,
            String publicUrl,
            Instant externalCreatedAt,
            int imageCount
    ) {
        this(
                externalListingId,
                productSlug,
                null,
                publicUrl,
                externalCreatedAt,
                imageCount
        );
    }
}
