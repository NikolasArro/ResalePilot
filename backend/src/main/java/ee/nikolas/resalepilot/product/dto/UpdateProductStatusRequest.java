package ee.nikolas.resalepilot.product.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusRequest(

        @NotNull
        ProductStatus status

) {
}