package ee.nikolas.resalepilot.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaPublicationConfirmResponse(
        UUID preparationId,
        Long oldListingId,
        boolean published,
        String newProductUrl,
        String newShopSlug,
        String newProductSlug,
        Instant publishedAt,
        YagaPublicationStatus status
) {
}
