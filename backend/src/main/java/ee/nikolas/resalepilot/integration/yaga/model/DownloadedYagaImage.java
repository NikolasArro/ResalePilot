package ee.nikolas.resalepilot.integration.yaga.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record DownloadedYagaImage(
        String externalImageId,
        String sourceUrl,
        String fileName,
        String mimeType,
        long sizeBytes,
        Path path
) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
