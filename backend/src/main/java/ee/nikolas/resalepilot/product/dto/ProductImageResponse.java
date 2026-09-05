package ee.nikolas.resalepilot.product.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductImage;

import java.time.Instant;

public record ProductImageResponse(
        Long id,
        String driveFileId,
        String fileName,
        int displayOrder,
        boolean primary,
        Instant createdAt
) {

    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(
                image.getId(),
                image.getDriveFileId(),
                image.getFileName(),
                image.getDisplayOrder(),
                image.isPrimaryImage(),
                image.getCreatedAt()
        );
    }
}