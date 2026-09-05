package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

import java.time.Instant;
import java.util.UUID;

public record YagaHideConfirmResponse(
        UUID preparationId,
        Long oldListingId,
        Long newListingId,
        boolean hidden,
        String oldListingUrl,
        Instant hiddenAt,
        YagaHidingStatus status
) {
}
