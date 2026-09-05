package ee.nikolas.resalepilot.workflow.yaga.importlisting.dto;

import jakarta.validation.constraints.NotBlank;

public record YagaImportPreviewRequest(

        @NotBlank
        String productUrl

) {
}