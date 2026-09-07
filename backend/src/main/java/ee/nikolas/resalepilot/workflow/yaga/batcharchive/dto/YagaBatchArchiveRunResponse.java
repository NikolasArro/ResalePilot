package ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto;

import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record YagaBatchArchiveRunResponse(
        UUID runId,
        YagaBatchArchiveRunStatus status,
        int requestedMaxListings,
        int selectedJobCount,
        int archivedListingCount,
        int alreadyArchivedListingCount,
        int failedListingCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<YagaBatchArchiveJobResponse> jobs
) {
}
