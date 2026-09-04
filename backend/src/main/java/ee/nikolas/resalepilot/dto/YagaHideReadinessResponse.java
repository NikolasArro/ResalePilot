package ee.nikolas.resalepilot.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaHideReadinessResponse(
        UUID preparationId,
        YagaHidingStatus sessionStatus,
        String currentUrl,
        boolean targetStillValid,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        String controlText,
        String tagName,
        String typeAttribute,
        boolean readyForConfirmation,
        Instant inspectedAt
) {
}
