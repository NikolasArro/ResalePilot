package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaPublicationConfirmRequest(
        @NotBlank String confirmationToken,
        @NotBlank String confirmationPhrase
) {
}
