package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import java.time.Instant;

public record YagaRefreshOperatorReadinessResponse(
        String sessionStatus,
        boolean targetStillValid,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        boolean readyForConfirmation,
        Instant inspectedAt,
        String operationStage,
        String currentUrlHost,
        String currentUrlPath,
        String expectedShopSlug,
        String expectedProductSlug,
        boolean targetUrlMatchesExpected
) {
}
