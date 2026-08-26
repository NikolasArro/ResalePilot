package ee.nikolas.resalepilot.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
@EnableConfigurationProperties(GoogleDriveProperties.class)
public class GoogleDriveConfig {

    @Bean
    @ConditionalOnProperty(
            name = "google.drive.enabled",
            havingValue = "true"
    )
    public Drive googleDrive(
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
}