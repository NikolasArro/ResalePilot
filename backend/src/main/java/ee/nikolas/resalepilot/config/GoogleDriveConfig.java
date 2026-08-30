package ee.nikolas.resalepilot.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleDriveConfig {

    @Bean
    @ConditionalOnProperty(
            name = "google.drive.enabled",
            havingValue = "true"
    )
    public Drive googleDrive(
            GoogleDriveProperties properties
    ) throws IOException, GeneralSecurityException {

        if (properties.authMode() == GoogleDriveProperties.AuthMode.OAUTH) {
            return oauthDrive(properties);
        }

        return serviceAccountDrive(properties);
    }

    private Drive serviceAccountDrive(
            GoogleDriveProperties properties
    ) throws IOException, GeneralSecurityException {

        String credentialsPath = properties.credentialsPath();

        if (credentialsPath == null ||
                credentialsPath.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_DRIVE_CREDENTIALS_PATH is not configured"
            );
        }

        GoogleCredentials credentials;

        try (FileInputStream inputStream =
                     new FileInputStream(credentialsPath)) {

            credentials = GoogleCredentials
                    .fromStream(inputStream)
                    .createScoped(
                            List.of(DriveScopes.DRIVE_READONLY)
                    );
        }

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("ResalePilot")
                .build();
    }

    private Drive oauthDrive(
            GoogleDriveProperties properties
    ) throws IOException, GeneralSecurityException {

        String clientSecretsPath =
                properties.oauthClientSecretsPath();

        if (clientSecretsPath == null ||
                clientSecretsPath.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_DRIVE_OAUTH_CLIENT_SECRETS_PATH is not configured"
            );
        }

        String tokensDirectory =
                properties.oauthTokensDirectory();

        if (tokensDirectory == null ||
                tokensDirectory.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_DRIVE_OAUTH_TOKENS_DIRECTORY is not configured"
            );
        }

        GsonFactory jsonFactory =
                GsonFactory.getDefaultInstance();

        GoogleClientSecrets clientSecrets;

        try (FileReader reader =
                     new FileReader(clientSecretsPath)) {

            clientSecrets = GoogleClientSecrets.load(
                    jsonFactory,
                    reader
            );
        }

        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        jsonFactory,
                        clientSecrets,
                        List.of(DriveScopes.DRIVE_FILE)
                )
                        .setDataStoreFactory(
                                new FileDataStoreFactory(
                                        new java.io.File(tokensDirectory)
                                )
                        )
                        .setAccessType("offline")
                        .build();

        LocalServerReceiver receiver =
                new LocalServerReceiver.Builder()
                        .setPort(8888)
                        .build();

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                jsonFactory,
                new AuthorizationCodeInstalledApp(
                        flow,
                        receiver
                ).authorize("user")
        )
                .setApplicationName("ResalePilot")
                .build();
    }
}
