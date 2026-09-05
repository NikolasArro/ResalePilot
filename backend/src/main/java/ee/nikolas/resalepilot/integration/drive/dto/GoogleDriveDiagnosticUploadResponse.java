package ee.nikolas.resalepilot.integration.drive.dto;

public record GoogleDriveDiagnosticUploadResponse(
        String fileId,
        String fileName,
        boolean uploaded,
        boolean deleted
) {
}
