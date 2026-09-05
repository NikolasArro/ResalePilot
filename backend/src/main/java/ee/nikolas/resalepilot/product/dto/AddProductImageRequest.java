package ee.nikolas.resalepilot.product.dto;

import ee.nikolas.resalepilot.product.entity.Product;

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