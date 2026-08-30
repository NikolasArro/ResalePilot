package ee.nikolas.resalepilot.dto;

public record GoogleDriveDiagnosticUploadResponse(
        String fileId,
        String fileName,
        boolean uploaded,
        boolean deleted
) {
}
