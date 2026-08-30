package ee.nikolas.resalepilot.service;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import ee.nikolas.resalepilot.dto.GoogleDriveFileResponse;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.exception.GoogleDriveFileNotFoundException;
import ee.nikolas.resalepilot.exception.InvalidGoogleDriveFileException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@ConditionalOnProperty(
        name = "google.drive.enabled",
        havingValue = "true"
)
public class GoogleDriveService {

    private final Drive drive;

    public GoogleDriveService(Drive drive) {
        this.drive = drive;
    }

    public GoogleDriveFileResponse getFileMetadata(String fileId) {
        try {
            File file = drive.files()
                    .get(fileId)
                    .setFields(
                            "id,name,mimeType,size,modifiedTime"
                    )
                    .execute();

            String modifiedTime =
                    file.getModifiedTime() == null
                            ? null
                            : file.getModifiedTime().toStringRfc3339();

            return new GoogleDriveFileResponse(
                    file.getId(),
                    file.getName(),
                    file.getMimeType(),
                    file.getSize(),
                    modifiedTime
            );

        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404) {
                throw new GoogleDriveFileNotFoundException(fileId);
            }

            throw new GoogleDriveAccessException(
                    "Google Drive rejected access to file: " + fileId,
                    exception
            );

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to read Google Drive file: " + fileId,
                    exception
            );
        }
    }

    public DownloadedDriveFile downloadToTemporaryFile(
            String fileId
    ) {
        GoogleDriveFileResponse metadata =
                getFileMetadata(fileId);

        String suffix = getImageSuffix(
                fileId,
                metadata.mimeType()
        );

        Path temporaryFile = null;

        try {
            temporaryFile = Files.createTempFile(
                    "resalepilot-",
                    suffix
            );

            try (OutputStream outputStream =
                         Files.newOutputStream(temporaryFile)) {

                drive.files()
                        .get(fileId)
                        .executeMediaAndDownloadTo(outputStream);
            }

            return new DownloadedDriveFile(
                    fileId,
                    metadata.name(),
                    metadata.mimeType(),
                    temporaryFile
            );

        } catch (IOException exception) {
            deletePartialFile(temporaryFile);

            throw new GoogleDriveAccessException(
                    "Failed to download Google Drive file: " + fileId,
                    exception
            );
        }
    }

    private String getImageSuffix(
            String fileId,
            String mimeType
    ) {
        if (mimeType == null) {
            throw new InvalidGoogleDriveFileException(
                    fileId,
                    null
            );
        }

        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default ->
                    throw new InvalidGoogleDriveFileException(
                            fileId,
                            mimeType
                    );
        };
    }

    private void deletePartialFile(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Не заменяем исходную ошибку ошибкой очистки.
        }
    }
}