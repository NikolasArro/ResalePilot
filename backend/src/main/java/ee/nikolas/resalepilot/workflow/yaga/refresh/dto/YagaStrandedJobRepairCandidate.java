package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import java.util.UUID;

public record YagaStrandedJobRepairCandidate(
        UUID runId,
        UUID jobId,
        Long sourceListingId,
        YagaRefreshJobStatus targetStatus
) { }
