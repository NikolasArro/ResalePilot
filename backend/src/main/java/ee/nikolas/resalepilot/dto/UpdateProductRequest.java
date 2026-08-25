package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.ProductCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateProductRequest(

        @NotBlank
        @Size(max = 30)
        String sku,

        @NotBlank
        @Size(max = 150)
        String title,

        String description,

        @Size(max = 100)
        String category,

        @Size(max = 100)
        String brand,

        @Size(max = 50)
        String size,

        ProductCondition condition,

        @Size(max = 50)
        String color,

        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal purchasePrice,

        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal askingPrice,

        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal minimumPrice,

        LocalDate acquiredAt
) {
}