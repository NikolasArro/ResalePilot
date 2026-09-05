package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

import java.time.Instant;

public record YagaHideReconcileResponse(
        Long oldListingId,
        Long newListingId,
        boolean hidden,
        String oldListingUrl,
        Instant hiddenAt,
        YagaHidingStatus status
) {
}
