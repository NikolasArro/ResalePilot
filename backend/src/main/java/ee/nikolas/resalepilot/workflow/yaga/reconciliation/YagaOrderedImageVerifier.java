package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class YagaOrderedImageVerifier {
    private static final int MAX_IMAGE_BYTES = 10_000_000;
    private final ImageFetcher fetcher;

    @Autowired
    public YagaOrderedImageVerifier() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.fetcher = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20)).GET().build();
            try {
                HttpResponse<java.io.InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    if (response.statusCode() != 200) {
                        throw invalid("Yaga image could not be read");
                    }
                    byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
                    if (bytes.length > MAX_IMAGE_BYTES) {
                        throw invalid("Yaga image is too large to verify");
                    }
                    return bytes;
                }
            } catch (IOException exception) {
                throw invalid("Yaga image could not be read");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw invalid("Yaga image verification was interrupted");
            }
        };
    }

    YagaOrderedImageVerifier(ImageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public void verify(String sourceSlug, List<YagaImportedProductData.Image> source,
                       String manualSlug, List<YagaImportedProductData.Image> manual) {
        if (source == null || manual == null || source.isEmpty() || source.size() != manual.size()) {
            throw invalid("Yaga image counts differ");
        }
        Set<Fingerprint> uniqueSource = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            Fingerprint expected = fingerprint(sourceSlug, source.get(index));
            Fingerprint actual = fingerprint(manualSlug, manual.get(index));
            if (!uniqueSource.add(expected) || !expected.equals(actual)) {
                throw invalid("Yaga image identity or order differs");
            }
        }
    }

    private Fingerprint fingerprint(String slug, YagaImportedProductData.Image image) {
        URI uri = imageUri(slug, image);
        byte[] bytes = fetcher.fetch(uri);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw invalid("Yaga image could not be read");
        }
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null || original.getWidth() < 9 || original.getHeight() < 8) {
                throw invalid("Yaga image could not be decoded");
            }
            BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(original, 0, 0, 9, 8, null);
            } finally {
                graphics.dispose();
            }
            long differenceHash = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    differenceHash <<= 1;
                    if (brightness(scaled.getRGB(x, y)) > brightness(scaled.getRGB(x + 1, y))) {
                        differenceHash |= 1;
                    }
                }
            }
            return new Fingerprint(original.getWidth(), original.getHeight(), differenceHash);
        } catch (IOException exception) {
            throw invalid("Yaga image could not be decoded");
        }
    }

    private URI imageUri(String slug, YagaImportedProductData.Image image) {
        if (slug == null || !slug.matches("[a-zA-Z0-9_-]+") || image == null ||
                image.id() == null || !image.id().matches("[a-zA-Z0-9_-]+") ||
                image.originalUrl() == null) {
            throw invalid("Yaga image identity is invalid");
        }
        try {
            URI uri = URI.create(image.originalUrl());
            String path = uri.getPath();
            if (!"https".equals(uri.getScheme()) || !"images.yaga.ee".equals(uri.getHost()) ||
                    uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null ||
                    uri.getFragment() != null || path == null ||
                    !(path.equals("/" + slug + "/" + image.id() + ".jpeg") ||
                            path.equals("/" + slug + "/" + image.id() + ".jpg") ||
                            path.equals("/" + slug + "/" + image.id() + ".png"))) {
                throw invalid("Yaga image URL is invalid");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw invalid("Yaga image URL is invalid");
        }
    }

    private int brightness(int rgb) {
        return 299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255);
    }

    private static YagaRefreshInvalidStateException invalid(String message) {
        return new YagaRefreshInvalidStateException(message);
    }

    record Fingerprint(int width, int height, long differenceHash) {
    }

    @FunctionalInterface
    interface ImageFetcher {
        byte[] fetch(URI uri);
    }
}
