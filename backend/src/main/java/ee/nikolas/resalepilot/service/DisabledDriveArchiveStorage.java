package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.yaga.DownloadedYagaImage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(
        name = "google.drive.yaga-archive-upload-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class DisabledDriveArchiveStorage
        implements DriveArchiveStorage {

    @Override
    public void verifyAvailable() {
        throw new GoogleDriveAccessException(
                "Yaga image archive upload is disabled"
        );
    }

    @Override
    public List<ArchivedDriveFile> uploadYagaImages(
            String productSku,
            Long marketplaceListingId,
            List<DownloadedYagaImage> images
    ) {
        throw new GoogleDriveAccessException(
                "Yaga image archive upload is disabled"
        );
    }

    @Override
    public void deleteCreatedFiles(List<String> driveFileIds) {
        // No files can be created while upload is disabled.
    }
}
