package ee.nikolas.resalepilot.integration.yaga.model;

import ee.nikolas.resalepilot.marketplace.entity.YagaDeliverySettings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record YagaImportedProductData(
        Long externalId,
        String shopSlug,
        String productSlug,
        String title,
        String description,
        BigDecimal price,
        String currency,
        String status,
        Condition condition,
        List<Category> categoryPath,
        List<Image> images,
        Instant createdAt,
        Instant updatedAt,
        Instant hiddenAt,
        Instant deletedAt,
        Integer likeCount,
        String size,
        String brand,
        List<String> colors,
        List<String> materials,
        YagaDeliverySettings deliverySettings
) {
    public YagaImportedProductData {
        colors = colors == null ? List.of() : List.copyOf(colors);
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    public YagaImportedProductData(
            Long externalId, String shopSlug, String productSlug,
            String title, String description, BigDecimal price,
            String currency, String status, Condition condition,
            List<Category> categoryPath, List<Image> images,
            Instant createdAt, Instant updatedAt, Instant hiddenAt,
            Instant deletedAt, Integer likeCount, String size,
            String brand, List<String> colors, List<String> materials
    ) {
        this(externalId, shopSlug, productSlug, title, description, price,
                currency, status, condition, categoryPath, images,
                createdAt, updatedAt, hiddenAt, deletedAt, likeCount,
                size, brand, colors, materials, null);
    }

    public YagaImportedProductData(
            Long externalId, String shopSlug, String productSlug,
            String title, String description, BigDecimal price,
            String currency, String status, Condition condition,
            List<Category> categoryPath, List<Image> images,
            Instant createdAt, Instant updatedAt, Instant hiddenAt,
            Instant deletedAt, Integer likeCount
    ) {
        this(externalId, shopSlug, productSlug, title, description, price,
                currency, status, condition, categoryPath, images,
                createdAt, updatedAt, hiddenAt, deletedAt, likeCount,
                null, null, List.of(), List.of());
    }

    public YagaImportedProductData(
            Long externalId,
            String shopSlug,
            String productSlug,
            String title,
            String description,
            BigDecimal price,
            String currency,
            String status,
            Condition condition,
            List<Category> categoryPath,
            List<Image> images,
            Instant createdAt,
            Instant updatedAt,
            Instant hiddenAt,
            Instant deletedAt
    ) {
        this(
                externalId,
                shopSlug,
                productSlug,
                title,
                description,
                price,
                currency,
                status,
                condition,
                categoryPath,
                images,
                createdAt,
                updatedAt,
                hiddenAt,
                deletedAt,
                null
        );
    }

    public YagaImportedProductData(
            Long externalId,
            String shopSlug,
            String productSlug,
            String description,
            BigDecimal price,
            String currency,
            String status,
            Condition condition,
            List<Category> categoryPath,
            List<Image> images,
            Instant createdAt,
            Instant updatedAt,
            Instant hiddenAt,
            Instant deletedAt
    ) {
        this(
                externalId,
                shopSlug,
                productSlug,
                null,
                description,
                price,
                currency,
                status,
                condition,
                categoryPath,
                images,
                createdAt,
                updatedAt,
                hiddenAt,
                deletedAt,
                null
        );
    }

    public record Condition(
            Long id,
            String name
    ) {
    }

    public record Category(
            Long id,
            Long parentId,
            String title,
            List<String> enabledFields
    ) {
    }

    public record Image(
            String id,
            String originalUrl,
            String fileName
    ) {
    }
}
