package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusRequest(

        @NotNull
        ProductStatus status

) {
}