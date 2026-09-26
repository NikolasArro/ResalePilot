package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto;

import java.time.Instant;

public record YagaShopDiscoveredListingResponse(
        String externalListingId,
        String productSlug,
        String title,
        String publicUrl,
        Instant externalCreatedAt,
        int imageCount,
        Integer likeCount
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
                imageCount,
                null
        );
    }

    public YagaShopDiscoveredListingResponse(
            String externalListingId,
            String productSlug,
            String title,
            String publicUrl,
            Instant externalCreatedAt,
            int imageCount
    ) {
        this(
                externalListingId,
                productSlug,
                title,
                publicUrl,
                externalCreatedAt,
                imageCount,
                null
        );
    }
}
