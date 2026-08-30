package ee.nikolas.resalepilot.dto;

import java.util.List;

public record YagaArchiveImagesResponse(
        Long marketplaceListingId,
        int alreadyLinkedImageCount,
        int archivedImageCount,
        int totalImageCount,
        List<YagaArchivedImageResponse> images
) {
}
