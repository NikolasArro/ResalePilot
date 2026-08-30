package ee.nikolas.resalepilot.service;

public record ArchivedDriveFile(
        String externalImageId,
        String sourceUrl,
        String originalFileName,
        String driveFileId
) {
}
