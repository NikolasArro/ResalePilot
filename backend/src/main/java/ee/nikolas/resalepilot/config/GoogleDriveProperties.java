package ee.nikolas.resalepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.drive")
public record GoogleDriveProperties(
        boolean enabled,
        String credentialsPath
) {
}