package ee.nikolas.resalepilot.workflow.yaga.shopimport.dto;

import java.util.List;

public record YagaAccountBulkImportResponse(
        Long accountId,
        String shopSlug,
        int discovered,
        int discoveredTotal,
        int processedCount,
        int created,
        int updated,
        int skipped,
        int failed,
        List<YagaAccountBulkImportFailureResponse> failures
) {
}
