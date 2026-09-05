package ee.nikolas.resalepilot.integration.yaga.downloader;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yaga.images")
public record YagaImageProperties(
        int maxImages,
        long maxFileSizeBytes,
        long maxBatchSizeBytes
) {
}
