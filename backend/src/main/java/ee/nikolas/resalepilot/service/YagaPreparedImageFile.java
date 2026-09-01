package ee.nikolas.resalepilot.service;

import java.nio.file.Path;

public record YagaPreparedImageFile(
        String driveFileId,
        String fileName,
        int displayOrder,
        boolean primary,
        Path path
) {
}
