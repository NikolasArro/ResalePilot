package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import java.util.UUID;

public record YagaRefreshHideRecoveryResponse(UUID runId, UUID jobId, Long newListingId,
        YagaRefreshRunStatus runStatus, YagaRefreshJobStatus jobStatus, boolean recovered) { }
