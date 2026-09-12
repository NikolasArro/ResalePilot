package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaRefreshPublicationReconcileRequest(
        @NotBlank String publicUrl
) {
}
