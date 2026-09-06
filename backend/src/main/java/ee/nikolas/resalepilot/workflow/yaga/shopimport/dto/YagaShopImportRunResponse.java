package ee.nikolas.resalepilot.workflow.yaga.shopimport.dto;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record YagaShopImportRunResponse(
        UUID runId,
        String shopSlug,
        YagaShopImportRunStatus status,
        int requestedMaxItems,
        int selectedItemCount,
        int importedCount,
        int existingCount,
        int skippedCount,
        int failedCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<YagaShopImportItemResponse> items
) {
}
