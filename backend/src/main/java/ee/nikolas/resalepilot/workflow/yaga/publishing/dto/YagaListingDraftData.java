package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;

import java.math.BigDecimal;
import java.util.List;

public record YagaListingDraftData(
        Long listingId,
        Long productId,
        String shopSlug,
        String description,
        BigDecimal askingPrice,
        String currency,
        ProductCondition condition,
        List<String> categoryPath,
        List<Image> images
) {

    public record Image(
            String driveFileId,
            String fileName,
            int displayOrder,
            boolean primary
    ) {
    }
}
