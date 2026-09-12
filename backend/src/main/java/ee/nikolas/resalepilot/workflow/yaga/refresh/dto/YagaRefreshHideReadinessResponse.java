package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshHideReadinessResponse(
        UUID preparationId,
        YagaHidingStatus sessionStatus,
        boolean targetStillValid,
        int candidateCount,
        int visibleCandidateCount,
        int enabledCandidateCount,
        boolean readyForConfirmation,
        Instant inspectedAt
) {
}
