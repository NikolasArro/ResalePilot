package ee.nikolas.resalepilot.service;

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
