package ee.nikolas.resalepilot.integration.drive.storage;

import ee.nikolas.resalepilot.integration.drive.client.GoogleDriveFileClient;
import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;
import ee.nikolas.resalepilot.integration.drive.model.UploadedDriveFile;

import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleDriveArchiveStorageTest {

    @Mock
    private GoogleDriveFileClient fileClient;

    @Test
    void uploadsYagaImagesWithStableFileNames()
            throws Exception {

        GoogleDriveArchiveStorage storage =
                new GoogleDriveArchiveStorage(fileClient);
        Path path = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        DownloadedYagaImage image =
                downloaded("external-1", path);

        ArgumentCaptor<String> fileNameCaptor =
                ArgumentCaptor.forClass(String.class);

        when(fileClient.ensureAppFolderId())
                .thenReturn("folder-1");
        when(fileClient.uploadFile(
                eq("folder-1"),
                fileNameCaptor.capture(),
                eq("image/jpeg"),
                eq(path)
        ))
                .thenReturn(
                        new UploadedDriveFile(
                                "drive-1",
                                "RP-000001-yaga-10-01.jpg"
                        )
                );

        List<ArchivedDriveFile> result =
                storage.uploadYagaImages(
                        "RP-000001",
                        10L,
                        List.of(image)
                );

        assertThat(fileNameCaptor.getValue())
                .isEqualTo("RP-000001-yaga-10-01.jpg");
        assertThat(result)
                .extracting(ArchivedDriveFile::driveFileId)
                .containsExactly("drive-1");
    }

    @Test
    void deletesOnlyFilesCreatedByCurrentBatchWhenUploadFails()
            throws Exception {

        GoogleDriveArchiveStorage storage =
                new GoogleDriveArchiveStorage(fileClient);
        Path firstPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );
        Path secondPath = Files.createTempFile(
                "resalepilot-test-",
                ".jpg"
        );

        when(fileClient.ensureAppFolderId())
                .thenReturn("folder-1");
        when(fileClient.uploadFile(
                eq("folder-1"),
                eq("RP-000001-yaga-10-01.jpg"),
                eq("image/jpeg"),
                eq(firstPath)
        ))
                .thenReturn(
                        new UploadedDriveFile(
                                "drive-1",
                                "RP-000001-yaga-10-01.jpg"
                        )
                );
        when(fileClient.uploadFile(
                eq("folder-1"),
                eq("RP-000001-yaga-10-02.jpg"),
                eq("image/jpeg"),
                eq(secondPath)
        ))
                .thenThrow(
                        new GoogleDriveAccessException("upload failed")
                );

        assertThatThrownBy(() ->
                storage.uploadYagaImages(
                        "RP-000001",
                        10L,
                        List.of(
                                downloaded("external-1", firstPath),
                                downloaded("external-2", secondPath)
                        )
                )
        )
                .isInstanceOf(GoogleDriveAccessException.class)
                .hasMessage(
                        "Drive upload failed for Yaga image number 2 of 2"
                );

        verify(fileClient).deleteFile("drive-1");
        verify(fileClient, never()).deleteFile("drive-2");
    }

    private DownloadedYagaImage downloaded(
            String externalImageId,
            Path path
    ) {
        return new DownloadedYagaImage(
                externalImageId,
                "https://images.yaga.ee/" + externalImageId + ".jpg",
                externalImageId + ".jpg",
                "image/jpeg",
                3,
                path
        );
    }
}
