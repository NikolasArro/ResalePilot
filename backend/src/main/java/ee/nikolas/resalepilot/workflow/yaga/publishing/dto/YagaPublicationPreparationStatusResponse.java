package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

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
        String lastSafeErrorMessage,
        String valmisActionDiagnostic
) {
    public YagaPublicationPreparationStatusResponse(UUID preparationId, Long listingId,
            YagaPublicationStatus status, Instant createdAt, Instant expiresAt,
            String screenshotPath, String newProductUrl, String lastSafeErrorMessage) {
        this(preparationId, listingId, status, createdAt, expiresAt,
                screenshotPath, newProductUrl, lastSafeErrorMessage, null);
    }
}
