package ee.nikolas.resalepilot.product.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderProductImagesRequest(

        @NotEmpty
        List<@NotNull Long> imageIds

) {
}