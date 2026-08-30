package ee.nikolas.resalepilot.dto;

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
