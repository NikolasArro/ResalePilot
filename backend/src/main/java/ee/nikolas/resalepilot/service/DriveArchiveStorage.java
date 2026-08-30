package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.yaga.DownloadedYagaImage;

import java.util.List;

public interface DriveArchiveStorage {

    default void verifyAvailable() {
    }

    List<ArchivedDriveFile> uploadYagaImages(
            String productSku,
            Long marketplaceListingId,
            List<DownloadedYagaImage> images
    );

    void deleteCreatedFiles(
            List<String> driveFileIds
    );
}
