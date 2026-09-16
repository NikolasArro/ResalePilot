package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record YagaRefreshRunResponse(
        UUID runId,
        Long yagaAccountId,
        String shopSlug,
        YagaRefreshTriggerType triggerType,
        YagaRefreshRunMode mode,
        YagaRefreshRunStatus status,
        int requestedBatchSize,
        int selectedJobCount,
        Instant createdAt,
        Instant completedAt,
        List<YagaRefreshJobResponse> candidates
) {
    public YagaRefreshRunResponse(
            UUID runId,
            YagaRefreshTriggerType triggerType,
            YagaRefreshRunMode mode,
            YagaRefreshRunStatus status,
            int requestedBatchSize,
            int selectedJobCount,
            Instant createdAt,
            Instant completedAt,
            List<YagaRefreshJobResponse> candidates
    ) {
        this(
                runId,
                1L,
                "nik-ar",
                triggerType,
                mode,
                status,
                requestedBatchSize,
                selectedJobCount,
                createdAt,
                completedAt,
                candidates
        );
    }
}
