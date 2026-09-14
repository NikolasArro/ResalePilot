package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaRefreshPrepareNextResponse(
        UUID runId,
        UUID jobId,
        YagaRefreshOperatorStage stage,
        YagaRefreshOperatorNextAction nextAction,
        UUID preparationId,
        String confirmationToken,
        Instant expiresAt,
        boolean readyForConfirmation,
        YagaRefreshOperatorReadinessResponse safeReadiness
) {
}
