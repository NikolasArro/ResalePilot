package ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto;

import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJobStatus;

import java.time.Instant;
import java.util.UUID;

public record YagaBatchArchiveJobResponse(
        UUID jobId,
        int selectionOrder,
        Long productId,
        Long listingId,
        String productTitle,
        int expectedImageCount,
        int initiallyLinkedImageCount,
        YagaBatchArchiveJobStatus status,
        int archivedImageCount,
        int alreadyLinkedImageCount,
        int failedImageCount,
        int attemptCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String lastErrorCode,
        String lastSafeErrorMessage
) {
}
