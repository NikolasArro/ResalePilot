package ee.nikolas.resalepilot.integration.drive.dto;

public record GoogleDriveFileResponse(
        String id,
        String name,
        String mimeType,
        Long size,
        String modifiedTime
) {
}