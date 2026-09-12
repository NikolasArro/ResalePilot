package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaRefreshPublicationConfirmRequest(
        @NotBlank String confirmationToken,
        @NotBlank String confirmationPhrase
) {
}
