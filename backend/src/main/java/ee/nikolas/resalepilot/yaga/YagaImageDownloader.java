package ee.nikolas.resalepilot.yaga;

import ee.nikolas.resalepilot.config.YagaImageProperties;
import ee.nikolas.resalepilot.exception.YagaImageDownloadException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

@Component
public class YagaImageDownloader {

    private static final String ALLOWED_SCHEME = "https";
    private static final String ALLOWED_HOST = "images.yaga.ee";
    private static final int MAX_REDIRECTS = 5;

    private final YagaImageProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public YagaImageDownloader(YagaImageProperties properties) {
        this(
                properties,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    YagaImageDownloader(
            YagaImageProperties properties,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public List<DownloadedYagaImage> downloadAll(
            List<YagaImportedProductData.Image> images
    ) {
        if (images.size() > properties.maxImages()) {
            throw new YagaImageDownloadException(
                    "Yaga image count exceeds limit: " + images.size()
            );
        }

        List<DownloadedYagaImage> downloadedImages =
                new ArrayList<>();

        long totalBytes = 0;

        try {
            for (YagaImportedProductData.Image image : images) {
                DownloadedYagaImage downloadedImage =
                        download(image);

                totalBytes += downloadedImage.sizeBytes();

                if (totalBytes > properties.maxBatchSizeBytes()) {
                    downloadedImage.close();

                    throw new YagaImageDownloadException(
                            "Yaga image batch exceeds size limit"
                    );
                }

                downloadedImages.add(downloadedImage);
            }

            return List.copyOf(downloadedImages);

        } catch (RuntimeException | IOException exception) {
            closeQuietly(downloadedImages);

            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new YagaImageDownloadException(
                    "Failed to clean up downloaded Yaga images",
                    exception
            );
        }
    }

    public DownloadedYagaImage download(
            YagaImportedProductData.Image image
    ) {
        URI uri = validateUri(image.originalUrl());

        HttpResponse<InputStream> response =
                sendFollowingSafeRedirects(uri);

        validateContentLength(response.headers());

        String mimeType = readMimeType(response.headers());
        String suffix = suffixForMimeType(mimeType);

        Path temporaryFile = null;

        try (InputStream inputStream = response.body()) {
            temporaryFile = Files.createTempFile(
                    "resalepilot-yaga-",
                    suffix
            );

            long sizeBytes = copyWithLimit(
                    inputStream,
                    temporaryFile
            );

            validateSignature(
                    temporaryFile,
                    mimeType
            );

            return new DownloadedYagaImage(
                    image.id(),
                    image.originalUrl(),
                    image.fileName(),
                    mimeType,
                    sizeBytes,
                    temporaryFile
            );

        } catch (IOException exception) {
            deleteQuietly(temporaryFile);

            throw new YagaImageDownloadException(
                    "Failed to download Yaga image",
                    exception
            );

        } catch (RuntimeException exception) {
            deleteQuietly(temporaryFile);
            throw exception;
        }
    }

    private HttpResponse<InputStream> sendFollowingSafeRedirects(
            URI initialUri
    ) {
        URI currentUri = initialUri;

        for (int redirects = 0;
             redirects <= MAX_REDIRECTS;
             redirects++) {

            HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .timeout(Duration.ofSeconds(20))
                    .header(
                            "User-Agent",
                            "Mozilla/5.0 ResalePilot/1.0"
                    )
                    .header("Accept", "image/jpeg,image/png,image/webp")
                    .GET()
                    .build();

            try {
                HttpResponse<InputStream> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofInputStream()
                        );

                if (isRedirect(response.statusCode())) {
                    currentUri = redirectUri(
                            currentUri,
                            response.headers()
                    );
                    continue;
                }

                if (response.statusCode() != 200) {
                    throw new YagaImageDownloadException(
                            "Yaga image returned HTTP " +
                                    response.statusCode()
                    );
                }

                return response;

            } catch (IOException exception) {
                throw new YagaImageDownloadException(
                        "Failed to request Yaga image",
                        exception
                );

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                throw new YagaImageDownloadException(
                        "Yaga image request was interrupted",
                        exception
                );
            }
        }

        throw new YagaImageDownloadException(
                "Yaga image redirect limit exceeded"
        );
    }

    private URI redirectUri(
            URI currentUri,
            HttpHeaders headers
    ) {
        String location = headers.firstValue("Location")
                .orElseThrow(() ->
                        new YagaImageDownloadException(
                                "Yaga image redirect without Location"
                        )
                );

        URI redirectedUri =
                validateUri(currentUri.resolve(location).toString());

        return redirectedUri;
    }

    private URI validateUri(String value) {
        if (value == null || value.isBlank()) {
            throw new YagaImageDownloadException(
                    "Yaga image URL is required"
            );
        }

        URI uri;

        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new YagaImageDownloadException(
                    "Invalid Yaga image URL",
                    exception
            );
        }

        if (!ALLOWED_SCHEME.equalsIgnoreCase(uri.getScheme()) ||
                !ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new YagaImageDownloadException(
                    "Yaga image URL must use https://images.yaga.ee"
            );
        }

        return uri;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 ||
                statusCode == 302 ||
                statusCode == 303 ||
                statusCode == 307 ||
                statusCode == 308;
    }

    private void validateContentLength(HttpHeaders headers) {
        OptionalLong contentLength =
                headers.firstValueAsLong("Content-Length");

        if (contentLength.isPresent() &&
                contentLength.getAsLong() > properties.maxFileSizeBytes()) {
            throw new YagaImageDownloadException(
                    "Yaga image Content-Length exceeds file size limit"
            );
        }
    }

    private String readMimeType(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type")
                .orElseThrow(() ->
                        new YagaImageDownloadException(
                                "Yaga image Content-Type is missing"
                        )
                );

        String mimeType = contentType
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);

        suffixForMimeType(mimeType);

        return mimeType;
    }

    private String suffixForMimeType(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new YagaImageDownloadException(
                    "Unsupported Yaga image MIME type: " + mimeType
            );
        };
    }

    private long copyWithLimit(
            InputStream inputStream,
            Path temporaryFile
    ) throws IOException {
        byte[] buffer = new byte[8192];
        long totalBytes = 0;

        try (var outputStream =
                     Files.newOutputStream(temporaryFile)) {

            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalBytes += bytesRead;

                if (totalBytes > properties.maxFileSizeBytes()) {
                    throw new YagaImageDownloadException(
                            "Yaga image exceeds file size limit"
                    );
                }

                outputStream.write(
                        buffer,
                        0,
                        bytesRead
                );
            }
        }

        return totalBytes;
    }

    private void validateSignature(
            Path path,
            String mimeType
    ) throws IOException {
        byte[] signature = Files.readAllBytes(path);

        boolean valid = switch (mimeType) {
            case "image/jpeg" -> startsWith(
                    signature,
                    new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
            );
            case "image/png" -> startsWith(
                    signature,
                    new byte[]{
                            (byte) 0x89,
                            0x50,
                            0x4E,
                            0x47,
                            0x0D,
                            0x0A,
                            0x1A,
                            0x0A
                    }
            );
            case "image/webp" -> isWebp(signature);
            default -> false;
        };

        if (!valid) {
            throw new YagaImageDownloadException(
                    "Yaga image signature does not match MIME type"
            );
        }
    }

    private boolean startsWith(
            byte[] value,
            byte[] prefix
    ) {
        if (value.length < prefix.length) {
            return false;
        }

        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }

        return true;
    }

    private boolean isWebp(byte[] value) {
        return value.length >= 12 &&
                value[0] == 'R' &&
                value[1] == 'I' &&
                value[2] == 'F' &&
                value[3] == 'F' &&
                value[8] == 'W' &&
                value[9] == 'E' &&
                value[10] == 'B' &&
                value[11] == 'P';
    }

    private void closeQuietly(
            List<DownloadedYagaImage> images
    ) {
        for (DownloadedYagaImage image : images) {
            try {
                image.close();
            } catch (IOException ignored) {
                // Cleanup failure must not hide the original failure.
            }
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup failure must not hide the original failure.
        }
    }
}
