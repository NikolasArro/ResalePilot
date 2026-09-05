package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record YagaPublicationPreparationResponse(
        UUID preparationId,
        Long listingId,
        Long productId,
        int imageCount,
        List<String> categoryPath,
        ProductCondition condition,
        BigDecimal price,
        String screenshotPath,
        String confirmationToken,
        Instant expiresAt,
        YagaPublicationStatus status
) {
}
