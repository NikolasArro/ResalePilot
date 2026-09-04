package ee.nikolas.resalepilot.dto;

public record YagaHideConfirmRequest(
        String confirmationToken,
        String confirmationPhrase
) {
}
