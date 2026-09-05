package ee.nikolas.resalepilot.workflow.yaga.importlisting.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;
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