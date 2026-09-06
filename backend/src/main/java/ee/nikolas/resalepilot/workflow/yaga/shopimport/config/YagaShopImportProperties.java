package ee.nikolas.resalepilot.workflow.yaga.shopimport.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "yaga.shop-import")
public record YagaShopImportProperties(
        boolean enabled,
        @Min(1) @Max(100) int defaultMaxItems,
        @Min(1) @Max(100) int maxItems
) {
    public YagaShopImportProperties {
        if (defaultMaxItems < 1) {
            throw new IllegalArgumentException(
                    "yaga.shop-import.default-max-items must be at least 1"
            );
        }
        if (maxItems < 1 || maxItems > 100) {
            throw new IllegalArgumentException(
                    "yaga.shop-import.max-items must be between 1 and 100"
            );
        }
        if (defaultMaxItems > maxItems) {
            throw new IllegalArgumentException(
                    "yaga.shop-import.default-max-items must not exceed max-items"
            );
        }
    }
}
