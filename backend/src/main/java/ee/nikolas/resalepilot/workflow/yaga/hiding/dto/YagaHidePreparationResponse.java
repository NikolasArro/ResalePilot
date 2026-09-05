package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaHidePreparationResponse(
        UUID preparationId,
        Long oldListingId,
        Long newListingId,
        Long productId,
        String oldExternalListingId,
        String oldProductSlug,
        String newExternalListingId,
        String newProductSlug,
        String currentUrl,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        String controlText,
        String accessibleName,
        String tagName,
        String typeAttribute,
        boolean readyForConfirmation,
        String confirmationToken,
        Instant expiresAt,
        String status
) {
}
