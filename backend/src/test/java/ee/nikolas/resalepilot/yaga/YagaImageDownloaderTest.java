package ee.nikolas.resalepilot.yaga;

import ee.nikolas.resalepilot.config.YagaImageProperties;
import ee.nikolas.resalepilot.exception.YagaImageDownloadException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class YagaImageDownloaderTest {

    private final YagaImageProperties properties =
            new YagaImageProperties(
                    6,
                    10,
                    100
            );

    @Test
    void rejectsNonYagaImageHost() {
        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        new FakeHttpClient()
                );

        assertThatThrownBy(() ->
                downloader.download(
                        new YagaImportedProductData.Image(
                                "image-1",
                                "https://example.com/image.jpg",
                                "image.jpg"
                        )
                )
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage(
                        "Yaga image URL must use https://images.yaga.ee"
                );
    }

    @Test
    void rejectsRedirectToOtherHost() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                302,
                Map.of("Location", List.of("https://example.com/x.jpg")),
                new byte[0]
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        httpClient
                );

        assertThatThrownBy(() ->
                downloader.download(
                        new YagaImportedProductData.Image(
                                "image-1",
                                "https://images.yaga.ee/image.jpg",
                                "image.jpg"
                        )
                )
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage(
                        "Yaga image URL must use https://images.yaga.ee"
                );
    }

    @Test
    void rejectsLargeContentLength() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                200,
                Map.of(
                        "Content-Type",
                        List.of("image/jpeg"),
                        "Content-Length",
                        List.of("11")
                ),
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        httpClient
                );

        assertThatThrownBy(() ->
                downloader.download(
                        new YagaImportedProductData.Image(
                                "image-1",
                                "https://images.yaga.ee/image.jpg",
                                "image.jpg"
                        )
                )
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage(
                        "Yaga image Content-Length exceeds file size limit"
                );
    }

    @Test
    void rejectsTooManyImages() {
        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        new FakeHttpClient()
                );

        List<YagaImportedProductData.Image> images =
                java.util.stream.IntStream.range(0, 7)
                        .mapToObj(index ->
                                new YagaImportedProductData.Image(
                                        "image-" + index,
                                        "https://images.yaga.ee/image-" +
                                                index +
                                                ".jpg",
                                        "image-" + index + ".jpg"
                                )
                        )
                        .toList();

        assertThatThrownBy(() ->
                downloader.downloadAll(images)
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage("Yaga image count exceeds limit: 7");
    }

    @Test
    void rejectsBatchOverLimitAndCleansPreviousFiles()
            throws Exception {

        YagaImageProperties smallBatchProperties =
                new YagaImageProperties(
                        6,
                        10,
                        5
                );
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                200,
                Map.of("Content-Type", List.of("image/jpeg")),
                jpegBytes(3)
        );
        httpClient.add(
                200,
                Map.of("Content-Type", List.of("image/jpeg")),
                jpegBytes(3)
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        smallBatchProperties,
                        httpClient
                );

        assertThatThrownBy(() ->
                downloader.downloadAll(List.of(
                        image("image-1"),
                        image("image-2")
                ))
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage("Yaga image batch exceeds size limit");
    }

    @Test
    void rejectsMimeSignatureMismatch() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                200,
                Map.of("Content-Type", List.of("image/png")),
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        httpClient
                );

        assertThatThrownBy(() ->
                downloader.download(image("image-1"))
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage(
                        "Yaga image signature does not match MIME type"
                );
    }

    @Test
    void followsSafeRedirectWithinYagaImagesHost()
            throws Exception {

        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                302,
                Map.of(
                        "Location",
                        List.of("https://images.yaga.ee/final.jpg")
                ),
                new byte[0]
        );
        httpClient.add(
                200,
                Map.of("Content-Type", List.of("image/jpeg")),
                jpegBytes(3)
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        httpClient
                );

        DownloadedYagaImage downloadedImage =
                downloader.download(image("image-1"));

        try (downloadedImage) {
            assertThat(downloadedImage.mimeType())
                    .isEqualTo("image/jpeg");
            assertThat(Files.exists(downloadedImage.path()))
                    .isTrue();
        }
    }

    @Test
    void rejectsActualBytesOverLimit() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.add(
                200,
                Map.of(
                        "Content-Type",
                        List.of("image/jpeg")
                ),
                new byte[]{
                        (byte) 0xFF,
                        (byte) 0xD8,
                        (byte) 0xFF,
                        1,
                        2,
                        3,
                        4,
                        5,
                        6,
                        7,
                        8
                }
        );

        YagaImageDownloader downloader =
                new YagaImageDownloader(
                        properties,
                        httpClient
                );

        assertThatThrownBy(() ->
                downloader.download(
                        new YagaImportedProductData.Image(
                                "image-1",
                                "https://images.yaga.ee/image.jpg",
                                "image.jpg"
                        )
                )
        )
                .isInstanceOf(YagaImageDownloadException.class)
                .hasMessage("Yaga image exceeds file size limit");
    }

    private static final class FakeHttpClient
            extends HttpClient {

        private final Queue<FakeResponse> responses =
                new ArrayDeque<>();

        void add(
                int statusCode,
                Map<String, List<String>> headers,
                byte[] body
        ) {
            responses.add(
                    new FakeResponse(
                            statusCode,
                            headers,
                            body
                    )
            );
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response =
                    (HttpResponse<T>) responses.remove();

            return response;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>>
        sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>>
        sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeResponse(
            int statusCode,
            Map<String, List<String>> headerValues,
            byte[] bodyBytes
    ) implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(
                    headerValues,
                    (name, value) -> true
            );
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bodyBytes);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://images.yaga.ee/image.jpg");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private YagaImportedProductData.Image image(String id) {
        return new YagaImportedProductData.Image(
                id,
                "https://images.yaga.ee/" + id + ".jpg",
                id + ".jpg"
        );
    }

    private byte[] jpegBytes(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;

        return bytes;
    }
}
