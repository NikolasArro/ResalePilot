package ee.nikolas.resalepilot.workflow.yaga.shopimport.dto;

public record YagaAccountBulkImportFailureResponse(
        String externalListingId,
        String productSlug,
        String errorCode,
        String safeMessage
) {
}
