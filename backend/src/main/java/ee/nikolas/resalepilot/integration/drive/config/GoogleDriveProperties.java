package ee.nikolas.resalepilot.integration.drive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.drive")
public record GoogleDriveProperties(
        boolean enabled,
        AuthMode authMode,
        String credentialsPath,
        String oauthClientSecretsPath,
        String oauthTokensDirectory,
        String appFolderIdPath,
        String appFolderName,
        boolean yagaArchiveUploadEnabled,
        boolean diagnosticEnabled
) {
    public enum AuthMode {
        SERVICE_ACCOUNT,
        OAUTH
    }
}
