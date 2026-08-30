package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.ProductCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YagaImportRequest(

        @NotBlank
        String productUrl,

        @NotBlank
        @Size(max = 30)
        String sku,

        @NotBlank
        @Size(max = 150)
        String title,

        ProductCondition conditionOverride

) {
}