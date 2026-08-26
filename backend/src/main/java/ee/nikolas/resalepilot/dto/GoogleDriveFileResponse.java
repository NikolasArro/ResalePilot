package ee.nikolas.resalepilot.dto;

public record GoogleDriveFileResponse(
        String id,
        String name,
        String mimeType,
        Long size,
        String modifiedTime
) {
}