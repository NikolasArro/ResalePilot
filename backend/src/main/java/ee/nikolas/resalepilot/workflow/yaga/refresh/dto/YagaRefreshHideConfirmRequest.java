package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaRefreshHideConfirmRequest(
        @NotBlank String confirmationToken,
        @NotBlank String confirmationPhrase
) {
}
