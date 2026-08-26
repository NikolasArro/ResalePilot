package ee.nikolas.resalepilot.service;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import ee.nikolas.resalepilot.dto.GoogleDriveFileResponse;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.exception.GoogleDriveFileNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@ConditionalOnBean(Drive.class)
public class GoogleDriveService {

    private final Drive drive;

    public GoogleDriveService(Drive drive) {
        this.drive = drive;
    }

    public GoogleDriveFileResponse getFileMetadata(String fileId) {
        try {
            File file = drive.files()
                    .get(fileId)
                    .setFields(
                            "id,name,mimeType,size,modifiedTime"
                    )
                    .execute();

            String modifiedTime =
                    file.getModifiedTime() == null
                            ? null
                            : file.getModifiedTime().toStringRfc3339();

            return new GoogleDriveFileResponse(
                    file.getId(),
                    file.getName(),
                    file.getMimeType(),
                    file.getSize(),
                    modifiedTime
            );

        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404) {
                throw new GoogleDriveFileNotFoundException(fileId);
            }

            throw new GoogleDriveAccessException(
                    "Google Drive rejected access to file: " + fileId,
                    exception
            );

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to read Google Drive file: " + fileId,
                    exception
            );
        }
    }
}