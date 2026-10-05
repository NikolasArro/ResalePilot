package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.marketplace.entity.YagaDeliverySettings;

import java.math.BigDecimal;
import java.util.List;

public record YagaListingDraftData(
        Long listingId,
        Long yagaAccountId,
        Long productId,
        String shopSlug,
        String description,
        BigDecimal askingPrice,
        String currency,
        ProductCondition condition,
        List<String> categoryPath,
        List<Image> images,
        String size,
        String brand,
        String color,
        String material,
        YagaDeliverySettings deliverySettings
) {
    public YagaListingDraftData(
            Long listingId, Long yagaAccountId, Long productId,
            String shopSlug, String description, BigDecimal askingPrice,
            String currency, ProductCondition condition,
            List<String> categoryPath, List<Image> images,
            String size, String brand, String color, String material
    ) {
        this(listingId, yagaAccountId, productId, shopSlug, description,
                askingPrice, currency, condition, categoryPath, images,
                size, brand, color, material, null);
    }
    public YagaListingDraftData(
            Long listingId,
            Long yagaAccountId,
            Long productId,
            String shopSlug,
            String description,
            BigDecimal askingPrice,
            String currency,
            ProductCondition condition,
            List<String> categoryPath,
            List<Image> images
    ) {
        this(listingId, yagaAccountId, productId, shopSlug, description,
                askingPrice, currency, condition, categoryPath, images,
                null, null, null, null, null);
    }

    public YagaListingDraftData(
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
        this(
                listingId,
                1L,
                productId,
                shopSlug,
                description,
                askingPrice,
                currency,
                condition,
                categoryPath,
                images,
                null,
                null,
                null,
                null,
                null
        );
    }

    public record Image(
            String driveFileId,
            String fileName,
            int displayOrder,
            boolean primary
    ) {
    }
}
