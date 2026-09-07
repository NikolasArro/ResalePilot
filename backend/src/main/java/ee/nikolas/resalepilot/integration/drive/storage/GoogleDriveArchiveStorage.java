package ee.nikolas.resalepilot.integration.drive.storage;

import ee.nikolas.resalepilot.integration.drive.client.GoogleDriveFileClient;
import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;
import ee.nikolas.resalepilot.integration.drive.model.UploadedDriveFile;

import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@ConditionalOnProperty(
        name = "google.drive.yaga-archive-upload-enabled",
        havingValue = "true"
)
public class GoogleDriveArchiveStorage
        implements DriveArchiveStorage {

    private final GoogleDriveFileClient fileClient;

    public GoogleDriveArchiveStorage(
            GoogleDriveFileClient fileClient
    ) {
        this.fileClient = fileClient;
    }

    @Override
    public List<ArchivedDriveFile> uploadYagaImages(
            String productSku,
            Long marketplaceListingId,
            List<DownloadedYagaImage> images
    ) {
        String folderId = fileClient.ensureAppFolderId();
        List<ArchivedDriveFile> uploadedFiles =
                new ArrayList<>();

        try {
            for (int index = 0; index < images.size(); index++) {
                DownloadedYagaImage image = images.get(index);
                String name = buildFileName(
                        productSku,
                        marketplaceListingId,
                        index,
                        image
                );

                UploadedDriveFile uploadedFile =
                        fileClient.uploadFile(
                                folderId,
                                name,
                                image.mimeType(),
                                image.path()
                        );

                uploadedFiles.add(
                        new ArchivedDriveFile(
                                image.externalImageId(),
                                image.sourceUrl(),
                                uploadedFile.name(),
                                uploadedFile.id()
                        )
                );
            }

            return List.copyOf(uploadedFiles);

        } catch (RuntimeException exception) {
            int failedImageNumber = uploadedFiles.size() + 1;
            deleteCreatedFiles(
                    uploadedFiles.stream()
                            .map(ArchivedDriveFile::driveFileId)
                            .toList()
            );

            throw new GoogleDriveAccessException(
                    "Drive upload failed for Yaga image number " +
                            failedImageNumber + " of " + images.size(),
                    exception
            );
        }
    }

    @Override
    public void deleteCreatedFiles(List<String> driveFileIds) {
        for (String driveFileId : driveFileIds) {
            try {
                fileClient.deleteFile(driveFileId);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup of files created by this operation.
            }
        }
    }

    private String buildFileName(
            String productSku,
            Long marketplaceListingId,
            int index,
            DownloadedYagaImage image
    ) {
        String extension = switch (image.mimeType()
                .toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };

        return "%s-yaga-%d-%02d%s".formatted(
                productSku,
                marketplaceListingId,
                index + 1,
                extension
        );
    }
}
