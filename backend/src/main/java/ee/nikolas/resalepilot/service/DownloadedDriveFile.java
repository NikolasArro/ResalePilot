package ee.nikolas.resalepilot.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record DownloadedDriveFile(
        String driveFileId,
        String originalFileName,
        String mimeType,
        Path path
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}