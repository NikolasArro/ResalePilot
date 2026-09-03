package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.ProductCondition;

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
