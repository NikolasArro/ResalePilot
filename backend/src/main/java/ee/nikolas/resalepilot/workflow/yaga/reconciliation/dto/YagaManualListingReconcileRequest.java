package ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record YagaManualListingReconcileRequest(
        @NotNull Long sourceListingId,
        @NotNull Long manualExternalListingId,
        @NotBlank String publicUrl
) {
}
