package ee.nikolas.resalepilot.dto;

public record GoogleDriveDownloadCheckResponse(
        String fileId,
        String fileName,
        String mimeType,
        long downloadedBytes,
        boolean temporaryFileDeleted
) {
}