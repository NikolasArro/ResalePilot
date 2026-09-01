package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.ProductCondition;

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
