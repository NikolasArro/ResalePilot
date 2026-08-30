package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.GoogleDriveFileResponse;
import ee.nikolas.resalepilot.service.GoogleDriveService;
import ee.nikolas.resalepilot.dto.GoogleDriveDownloadCheckResponse;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.service.DownloadedDriveFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/google-drive/files")
@ConditionalOnProperty(
        name = "google.drive.enabled",
        havingValue = "true"
)
public class GoogleDriveController {

    private final GoogleDriveService googleDriveService;

    public GoogleDriveController(
            GoogleDriveService googleDriveService
    ) {
        this.googleDriveService = googleDriveService;
    }

    @GetMapping("/{fileId}")
    public GoogleDriveFileResponse getFileMetadata(
            @PathVariable String fileId
    ) {
        return googleDriveService.getFileMetadata(fileId);
    }

    @PostMapping("/{fileId}/download-check")
    public GoogleDriveDownloadCheckResponse checkDownload(
            @PathVariable String fileId
    ) {
        DownloadedDriveFile downloadedFile =
                googleDriveService
                        .downloadToTemporaryFile(fileId);

        Path temporaryPath = downloadedFile.path();
        long downloadedBytes;

        try (downloadedFile) {
            downloadedBytes = Files.size(temporaryPath);

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to verify downloaded file: " + fileId,
                    exception
            );
        }

        boolean temporaryFileDeleted =
                Files.notExists(temporaryPath);

        return new GoogleDriveDownloadCheckResponse(
                fileId,
                downloadedFile.originalFileName(),
                downloadedFile.mimeType(),
                downloadedBytes,
                temporaryFileDeleted
        );
    }
}