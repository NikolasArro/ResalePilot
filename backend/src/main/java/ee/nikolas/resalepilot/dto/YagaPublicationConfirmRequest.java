package ee.nikolas.resalepilot.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaPublicationConfirmRequest(
        @NotBlank String confirmationToken,
        @NotBlank String confirmationPhrase
) {
}
