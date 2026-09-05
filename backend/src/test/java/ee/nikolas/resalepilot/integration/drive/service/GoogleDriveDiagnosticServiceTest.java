package ee.nikolas.resalepilot.integration.drive.service;

import ee.nikolas.resalepilot.integration.drive.client.GoogleDriveFileClient;
import ee.nikolas.resalepilot.integration.drive.model.UploadedDriveFile;

import ee.nikolas.resalepilot.integration.drive.dto.GoogleDriveDiagnosticUploadResponse;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleDriveDiagnosticServiceTest {

    @Mock
    private GoogleDriveFileClient fileClient;

    @Test
    void uploadsAndDeletesSmallDiagnosticFile()
            throws Exception {

        GoogleDriveDiagnosticService service =
                new GoogleDriveDiagnosticService(fileClient);

        ArgumentCaptor<String> fileNameCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mimeTypeCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Path> pathCaptor =
                ArgumentCaptor.forClass(Path.class);
        AtomicReference<String> uploadedContent =
                new AtomicReference<>();

        when(fileClient.ensureAppFolderId())
                .thenReturn("folder-1");
        when(fileClient.uploadFile(
                eq("folder-1"),
                fileNameCaptor.capture(),
                mimeTypeCaptor.capture(),
                pathCaptor.capture()
        ))
                .thenAnswer(invocation -> {
                    uploadedContent.set(
                            Files.readString(
                                    invocation.getArgument(3)
                            )
                    );

                    return new UploadedDriveFile(
                            "drive-file-1",
                            "uploaded-test.txt"
                    );
                });

        GoogleDriveDiagnosticUploadResponse response =
                service.uploadAndDeleteTestFile();

        assertThat(response.fileId()).isEqualTo("drive-file-1");
        assertThat(response.fileName()).isEqualTo("uploaded-test.txt");
        assertThat(response.uploaded()).isTrue();
        assertThat(response.deleted()).isTrue();

        assertThat(fileNameCaptor.getValue())
                .startsWith("resalepilot-drive-upload-test-")
                .endsWith(".txt");
        assertThat(mimeTypeCaptor.getValue())
                .isEqualTo("text/plain");
        assertThat(uploadedContent.get())
                .isEqualTo("ResalePilot Drive upload test");

        verify(fileClient).deleteFile("drive-file-1");
        assertThat(Files.notExists(pathCaptor.getValue()))
                .isTrue();
    }

    @Test
    void reportsFileIdWhenDeleteFails()
            throws Exception {

        GoogleDriveDiagnosticService service =
                new GoogleDriveDiagnosticService(fileClient);

        ArgumentCaptor<Path> pathCaptor =
                ArgumentCaptor.forClass(Path.class);

        when(fileClient.ensureAppFolderId())
                .thenReturn("folder-1");
        when(fileClient.uploadFile(
                eq("folder-1"),
                anyString(),
                eq("text/plain"),
                pathCaptor.capture()
        ))
                .thenReturn(
                        new UploadedDriveFile(
                                "drive-file-1",
                                "uploaded-test.txt"
                        )
                );
        doThrow(new GoogleDriveAccessException("delete failed"))
                .when(fileClient)
                .deleteFile("drive-file-1");

        GoogleDriveDiagnosticUploadResponse response =
                service.uploadAndDeleteTestFile();

        assertThat(response.fileId()).isEqualTo("drive-file-1");
        assertThat(response.uploaded()).isTrue();
        assertThat(response.deleted()).isFalse();
        assertThat(Files.notExists(pathCaptor.getValue()))
                .isTrue();
    }
}
