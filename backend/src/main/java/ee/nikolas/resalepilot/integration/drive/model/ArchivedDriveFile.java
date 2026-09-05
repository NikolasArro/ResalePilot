package ee.nikolas.resalepilot.integration.drive.model;

public record ArchivedDriveFile(
        String externalImageId,
        String sourceUrl,
        String originalFileName,
        String driveFileId
) {
}
