package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaHidePreparationStatusResponse(
        UUID preparationId,
        Long oldListingId,
        Long newListingId,
        YagaHidingStatus status,
        Instant createdAt,
        Instant expiresAt,
        String currentUrl,
        String oldListingUrl,
        String lastSafeErrorMessage
) {
}
