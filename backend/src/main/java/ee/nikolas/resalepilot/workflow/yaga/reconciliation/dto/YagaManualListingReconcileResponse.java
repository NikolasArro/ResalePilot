package ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto;

import java.util.List;

public record YagaManualListingReconcileResponse(
        Long accountId,
        Long productId,
        Long currentListingId,
        String externalListingId,
        List<Long> inactiveListingIds,
        boolean reconciled
) {
}
