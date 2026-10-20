package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import java.util.List;
import java.util.UUID;

public record YagaStrandedJobRepairPreview(
        Long accountId,
        int autoSkippedCount,
        int dryRunCompletedCount,
        int refusedSelectedCount,
        List<UUID> eligibleJobIds,
        List<YagaStrandedJobRepairCandidate> candidates
) { }
