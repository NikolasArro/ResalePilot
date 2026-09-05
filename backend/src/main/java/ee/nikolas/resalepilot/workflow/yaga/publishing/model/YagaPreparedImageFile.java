package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import java.nio.file.Path;

public record YagaPreparedImageFile(
        String driveFileId,
        String fileName,
        int displayOrder,
        boolean primary,
        Path path
) {
}
