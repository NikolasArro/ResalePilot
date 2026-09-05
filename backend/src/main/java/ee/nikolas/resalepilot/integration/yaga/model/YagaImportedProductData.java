package ee.nikolas.resalepilot.integration.yaga.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record YagaImportedProductData(
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