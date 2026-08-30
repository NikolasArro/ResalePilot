package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.GoogleDriveDiagnosticUploadResponse;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "google.drive.diagnostic-enabled",
        havingValue = "true"
)
public class GoogleDriveDiagnosticService {

    private static final String TEST_CONTENT =
            "ResalePilot Drive upload test";

    private final GoogleDriveFileClient fileClient;

    public GoogleDriveDiagnosticService(
            GoogleDriveFileClient fileClient
    ) {
        this.fileClient = fileClient;
    }

    public GoogleDriveDiagnosticUploadResponse uploadAndDeleteTestFile() {
        String fileName = "resalepilot-drive-upload-test-" +
                UUID.randomUUID() +
                ".txt";

        Path temporaryFile = null;

        try {
            temporaryFile = Files.createTempFile(
                    "resalepilot-drive-upload-test-",
                    ".txt"
            );

            Files.writeString(
                    temporaryFile,
                    TEST_CONTENT,
                    StandardCharsets.UTF_8
            );

            String folderId = fileClient.ensureAppFolderId();

            UploadedDriveFile uploadedFile =
                    fileClient.uploadFile(
                            folderId,
                            fileName,
                            "text/plain",
                            temporaryFile
                    );

            boolean deleted = deleteUploadedFile(
                    uploadedFile.id()
            );

            return new GoogleDriveDiagnosticUploadResponse(
                    uploadedFile.id(),
                    uploadedFile.name(),
                    true,
                    deleted
            );

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to create Google Drive diagnostic file",
                    exception
            );

        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    private boolean deleteUploadedFile(String fileId) {
        try {
            fileClient.deleteFile(fileId);
            return true;

        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Local temp cleanup is best-effort after Drive state is known.
        }
    }
}
