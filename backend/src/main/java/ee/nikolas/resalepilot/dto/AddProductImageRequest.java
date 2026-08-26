package ee.nikolas.resalepilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddProductImageRequest(

        @NotBlank
        @Size(max = 255)
        String driveFileId,

        @Size(max = 255)
        String fileName,

        boolean primary

) {
}