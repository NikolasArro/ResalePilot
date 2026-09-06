package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "yaga.shop-discovery")
public record YagaShopDiscoveryProperties(
        boolean enabled,
        @Min(1) @Max(500) int maxPages,
        @Min(1) @Max(5000) int maxListings,
        @NotNull Duration requestDelay,
        @NotNull Duration requestTimeout
) {
    public YagaShopDiscoveryProperties {
        if (maxPages < 1 || maxPages > 500) {
            throw new IllegalArgumentException(
                    "yaga.shop-discovery.max-pages must be between 1 and 500"
            );
        }
        if (maxListings < 1 || maxListings > 5000) {
            throw new IllegalArgumentException(
                    "yaga.shop-discovery.max-listings must be between 1 and 5000"
            );
        }
        if (requestDelay == null || requestDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "yaga.shop-discovery.request-delay must not be negative"
            );
        }
        if (requestTimeout == null ||
                requestTimeout.isZero() ||
                requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "yaga.shop-discovery.request-timeout must be positive"
            );
        }
    }
}
