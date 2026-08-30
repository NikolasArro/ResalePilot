package ee.nikolas.resalepilot.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaImportPreviewRequest(

        @NotBlank
        String productUrl

) {
}