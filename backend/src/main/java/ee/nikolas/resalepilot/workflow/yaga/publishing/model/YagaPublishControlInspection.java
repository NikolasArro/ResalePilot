package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import java.time.Instant;

public record YagaPublishControlInspection(
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
