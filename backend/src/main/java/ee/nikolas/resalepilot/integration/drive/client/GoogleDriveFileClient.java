package ee.nikolas.resalepilot.integration.drive.client;

import ee.nikolas.resalepilot.integration.drive.model.UploadedDriveFile;

import java.nio.file.Path;

public interface GoogleDriveFileClient {

    String ensureAppFolderId();

    UploadedDriveFile uploadFile(
            String folderId,
            String fileName,
            String mimeType,
            Path path
    );

    void deleteFile(String fileId);
}
