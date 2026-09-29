package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaRefreshManualPublicationRecoveryRequest(
        @NotBlank String publicUrl,
        @NotBlank String confirmationPhrase
) {
}
