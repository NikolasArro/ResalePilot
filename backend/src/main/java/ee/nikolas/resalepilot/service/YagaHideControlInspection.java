package ee.nikolas.resalepilot.service;

import java.time.Instant;

public record YagaHideControlInspection(
        String currentUrl,
        String targetExternalListingId,
        String targetProductSlug,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        String controlText,
        String accessibleName,
        String tagName,
        String typeAttribute,
        boolean readyForConfirmation,
        Instant inspectedAt,
        YagaHideTargetDiagnostics targetDiagnostics
) {
}
