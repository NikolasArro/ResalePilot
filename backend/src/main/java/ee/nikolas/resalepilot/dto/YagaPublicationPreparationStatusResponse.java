package ee.nikolas.resalepilot.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaPublicationPreparationStatusResponse(
        UUID preparationId,
        Long listingId,
        YagaPublicationStatus status,
        Instant createdAt,
        Instant expiresAt,
        String screenshotPath,
        String newProductUrl,
        String lastSafeErrorMessage
) {
}
