package ee.nikolas.resalepilot.integration.drive.storage;

import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;

import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;

import java.util.List;

public interface DriveArchiveStorage {

    default void verifyAvailable() {
    }

    List<ArchivedDriveFile> uploadYagaImages(
            YagaAccount account,
            String productSku,
            Long marketplaceListingId,
            List<DownloadedYagaImage> images
    );

    void deleteCreatedFiles(
            List<String> driveFileIds
    );
}
