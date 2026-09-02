package ee.nikolas.resalepilot.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaPublishReadinessResponse(
        UUID preparationId,
        YagaPublicationStatus sessionStatus,
        String currentUrl,
        boolean formStillValid,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        String buttonText,
        String tagName,
        String typeAttribute,
        boolean readyForConfirmation,
        Instant inspectedAt
) {
}
