package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.entity.ProductCondition;
import ee.nikolas.resalepilot.entity.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ProductResponse(
        Long id,
        String sku,
        String title,
        String description,
        String category,
        String brand,
        String size,
        ProductCondition condition,
        String color,
        BigDecimal purchasePrice,
        BigDecimal askingPrice,
        BigDecimal minimumPrice,
        ProductStatus status,
        LocalDate acquiredAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getTitle(),
                product.getDescription(),
                product.getCategory(),
                product.getBrand(),
                product.getSize(),
                product.getCondition(),
                product.getColor(),
                product.getPurchasePrice(),
                product.getAskingPrice(),
                product.getMinimumPrice(),
                product.getStatus(),
                product.getAcquiredAt(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getVersion()
        );
    }
}