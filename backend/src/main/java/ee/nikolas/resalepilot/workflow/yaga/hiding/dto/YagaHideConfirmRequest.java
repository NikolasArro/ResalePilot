package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

public record YagaHideConfirmRequest(
        String confirmationToken,
        String confirmationPhrase
) {
}
