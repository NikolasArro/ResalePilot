package ee.nikolas.resalepilot.workflow.yaga.batcharchive.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "yaga.batch-archive")
public record YagaBatchArchiveProperties(
        boolean enabled,
        @Min(1) @Max(100) int defaultMaxListings,
        @Min(1) @Max(100) int maxListings
) {
    public YagaBatchArchiveProperties {
        if (defaultMaxListings < 1) {
            throw new IllegalArgumentException(
                    "yaga.batch-archive.default-max-listings must be at least 1"
            );
        }
        if (maxListings < 1 || maxListings > 100) {
            throw new IllegalArgumentException(
                    "yaga.batch-archive.max-listings must be between 1 and 100"
            );
        }
        if (defaultMaxListings > maxListings) {
            throw new IllegalArgumentException(
                    "yaga.batch-archive.default-max-listings must not exceed max-listings"
            );
        }
    }
}
