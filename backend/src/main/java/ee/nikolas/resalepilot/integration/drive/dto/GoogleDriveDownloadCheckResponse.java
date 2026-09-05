package ee.nikolas.resalepilot.integration.drive.dto;

public record GoogleDriveDownloadCheckResponse(
        String fileId,
        String fileName,
        String mimeType,
        long downloadedBytes,
        boolean temporaryFileDeleted
) {
}