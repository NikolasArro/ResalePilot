package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaOrderedImageVerifierTest {
    private final List<YagaImportedProductData.Image> source = List.of(
            image("source", "first"), image("source", "second"));
    private final List<YagaImportedProductData.Image> manual = List.of(
            image("manual", "one"), image("manual", "two"));

    @Test
    void acceptsSamePixelsInTheSameOrderAcrossDifferentYagaImageIds() {
        byte[] first = jpeg(false);
        byte[] second = jpeg(true);
        var verifier = verifier(Map.of(
                URI.create(source.get(0).originalUrl()), first,
                URI.create(source.get(1).originalUrl()), second,
                URI.create(manual.get(0).originalUrl()), first,
                URI.create(manual.get(1).originalUrl()), second));

        verifier.verify("source", source, "manual", manual);
    }

    @Test
    void rejectsImagesInTheWrongOrder() {
        byte[] first = jpeg(false);
        byte[] second = jpeg(true);
        var verifier = verifier(Map.of(
                URI.create(source.get(0).originalUrl()), first,
                URI.create(source.get(1).originalUrl()), second,
                URI.create(manual.get(0).originalUrl()), second,
                URI.create(manual.get(1).originalUrl()), first));

        assertThatThrownBy(() -> verifier.verify("source", source, "manual", manual))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("identity or order differs");
    }

    @Test
    void rejectsImageUrlOutsideExpectedYagaPathBeforeFetching() {
        byte[] first = jpeg(false);
        var verifier = new YagaOrderedImageVerifier(uri -> {
            if (uri.equals(URI.create(source.getFirst().originalUrl()))) return first;
            throw new AssertionError("Unexpected fetch");
        });
        var unsafe = List.of(new YagaImportedProductData.Image("one",
                "https://other.example/manual/one.jpeg", "one.jpeg"));

        assertThatThrownBy(() -> verifier.verify("source", List.of(source.getFirst()), "manual", unsafe))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("URL is invalid");
    }

    private YagaOrderedImageVerifier verifier(Map<URI, byte[]> bytes) {
        return new YagaOrderedImageVerifier(uri -> bytes.get(uri));
    }

    private YagaImportedProductData.Image image(String slug, String id) {
        return new YagaImportedProductData.Image(id,
                "https://images.yaga.ee/" + slug + "/" + id + ".jpeg", id + ".jpeg");
    }

    private byte[] jpeg(boolean reverse) {
        BufferedImage image = new BufferedImage(90, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = reverse ? 255 - x * 2 : x * 2;
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", output);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
