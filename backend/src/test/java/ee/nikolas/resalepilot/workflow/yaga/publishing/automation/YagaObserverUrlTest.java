package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.net.URISyntaxException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class YagaObserverUrlTest {
    @Test
    void validAbsoluteApiUrlKeepsOnlyItsPath() {
        assertThat(path("https://www.yaga.ee/api/product?token=SECRET#SECRET")).isEqualTo("/api/product");
        assertThat(path("https://yaga.ee/api/product/123/images")).isEqualTo("/api/product/123/images");
    }

    @ParameterizedTest
    @ValueSource(strings = {"%20", "%5Bproduct-slug%5D", "%23", "%3F", "%25", "%C3%A4"})
    void encodedPathsRemainEncodedAndNeverRequireASecondParse(String encoded) {
        String path = "/_next/static/chunks/pages/" + encoded + ".js";
        assertThat(path("https://www.yaga.ee" + path + "?value=%5B1%5D#fragment")).isEqualTo(path);
        assertThat(YagaPublicationSubmitObserver.safeUrl("https://www.yaga.ee" + path))
                .isEqualTo("https://www.yaga.ee" + path);
    }

    @Test
    void reproducesOldDecodeThenReparseFailure() {
        URI original = URI.create("https://www.yaga.ee/_next/static/chunks/pages/%5Bproduct-slug%5D.js");
        String oldSanitized = "https://" + original.getHost() + original.getPath();
        assertThat(oldSanitized).isEqualTo("https://www.yaga.ee/_next/static/chunks/pages/[product-slug].js");
        assertThatThrownBy(() -> URI.create(oldSanitized)).isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(URISyntaxException.class);
        assertThat(path(original.toString())).contains("%5Bproduct-slug%5D");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/product", "//www.yaga.ee/api/product", "http://www.yaga.ee/api/product",
            "https://www.yaga.ee.evil.example/api/product", "https://www.yaga.ee@evil.example/api/product",
            "https://user:SECRET@www.yaga.ee/api/product", "https://www.yaga.ee:8443/api/product",
            "https://www.yaga.ee/api/product/%zz", "https://www.yaga.ee/api/product with spaces"})
    void unsupportedOrMalformedUrlsAreIgnoredWithoutWeakeningOriginValidation(String url) {
        assertThat(path(url)).isEmpty();
    }

    @Test
    void malformedAssetDiagnosticOmitsQueriesAndFragments(CapturedOutput output) {
        assertThat(path("https://www.yaga.ee/_next/static/chunks/[bad].js?token=SECRET#SECRET")).isEmpty();
        assertThat(output).contains("sanitizedPath=/_next/static/chunks/[bad].js", "reason=INVALID_URI_SYNTAX")
                .doesNotContain("SECRET", "token=");
    }

    private String path(String url) {
        Request request = mock(Request.class);
        when(request.url()).thenReturn(url);
        return YagaDraftPreparationObserver.path(request);
    }
}
