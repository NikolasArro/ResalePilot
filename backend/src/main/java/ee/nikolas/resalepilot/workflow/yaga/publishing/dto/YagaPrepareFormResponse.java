package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;

import java.math.BigDecimal;
import java.util.List;

public record YagaPrepareFormResponse(
        Long listingId,
        Long productId,
        int imageCount,
        boolean descriptionFilled,
        List<String> categoryPath,
        ProductCondition condition,
        BigDecimal price,
        String screenshotPath,
        String status
) {
}
