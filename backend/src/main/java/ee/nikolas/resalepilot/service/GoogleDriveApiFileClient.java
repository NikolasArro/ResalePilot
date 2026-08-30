package ee.nikolas.resalepilot.service;

import com.google.api.client.http.FileContent;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import ee.nikolas.resalepilot.config.GoogleDriveProperties;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(
        name = "google.drive.enabled",
        havingValue = "true"
)
public class GoogleDriveApiFileClient
        implements GoogleDriveFileClient {

    private static final String FOLDER_MIME_TYPE =
            "application/vnd.google-apps.folder";

    private final Drive drive;
    private final GoogleDriveProperties properties;

    public GoogleDriveApiFileClient(
            Drive drive,
            GoogleDriveProperties properties
    ) {
        this.drive = drive;
        this.properties = properties;
    }

    @Override
    public String ensureAppFolderId() {
        Path folderIdPath = Path.of(
                properties.appFolderIdPath()
        );

        if (Files.exists(folderIdPath)) {
            try {
                String existingFolderId =
                        Files.readString(folderIdPath)
                                .trim();

                if (!existingFolderId.isBlank()) {
                    return existingFolderId;
                }

            } catch (IOException exception) {
                throw new GoogleDriveAccessException(
                        "Failed to read Google Drive app folder id",
                        exception
                );
            }
        }

        try {
            File folderMetadata = new File()
                    .setName(properties.appFolderName())
                    .setMimeType(FOLDER_MIME_TYPE)
                    .setCreatedTime(
                            new DateTime(Instant.now().toString())
                    );

            File folder = drive.files()
                    .create(folderMetadata)
                    .setFields("id,name")
                    .execute();

            Path parent = folderIdPath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(
                    folderIdPath,
                    folder.getId(),
                    StandardCharsets.UTF_8
            );

            return folder.getId();

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to create Google Drive app folder",
                    exception
            );
        }
    }

    @Override
    public UploadedDriveFile uploadFile(
            String folderId,
            String fileName,
            String mimeType,
            Path path
    ) {
        try {
            File metadata = new File()
                    .setName(fileName)
                    .setParents(List.of(folderId));

            FileContent content =
                    new FileContent(
                            mimeType,
                            path.toFile()
                    );

            File uploadedFile = drive.files()
                    .create(metadata, content)
                    .setFields("id,name")
                    .execute();

            return new UploadedDriveFile(
                    uploadedFile.getId(),
                    uploadedFile.getName()
            );

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to upload file to Google Drive",
                    exception
            );
        }
    }

    @Override
    public void deleteFile(String fileId) {
        try {
            drive.files()
                    .delete(fileId)
                    .execute();

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to delete Google Drive file: " + fileId,
                    exception
            );
        }
    }
}
