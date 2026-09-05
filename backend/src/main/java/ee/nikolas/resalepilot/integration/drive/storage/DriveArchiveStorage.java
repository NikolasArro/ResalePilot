package ee.nikolas.resalepilot.integration.drive.storage;

import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;

import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;

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
