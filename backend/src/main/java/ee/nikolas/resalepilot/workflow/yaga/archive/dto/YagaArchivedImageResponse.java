package ee.nikolas.resalepilot.workflow.yaga.archive.dto;

public record YagaArchivedImageResponse(
        Long marketplaceListingImageId,
        String externalImageId,
        String sourceUrl,
        Long productImageId,
        String driveFileId,
        String fileName,
        int displayOrder,
        boolean primary
) {
}
