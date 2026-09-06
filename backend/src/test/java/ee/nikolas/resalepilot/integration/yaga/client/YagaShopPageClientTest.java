package ee.nikolas.resalepilot.integration.yaga.client;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YagaShopPageClientTest {

    private final YagaShopPageClient client =
            new YagaShopPageClient(new ObjectMapper());

    @Test
    void parsesProductLinksAndNextPageFromShopHtml() {
        String html = """
                <a href="/nik-ar/toode/first">First</a>
                <a href="/nik-ar/toode/second">Second</a>
                <a href="/other-shop/toode/wrong">Wrong shop</a>
                <a href="/nik-ar?page=2">Next</a>
                """;

        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                html
        );

        assertThat(page.productLinks())
                .extracting("productSlug")
                .containsExactly("first", "second");
        assertThat(page.nextPageUrl())
                .isEqualTo("https://www.yaga.ee/nik-ar?page=2");
    }

    @Test
    void lastPageHasNoNextPage() {
        var page = client.parseShopPage(
                "nik-ar",
                2,
                "https://www.yaga.ee/nik-ar?page=2",
                "https://www.yaga.ee/nik-ar?page=2",
                200,
                "text/html",
                "<a href=\"/nik-ar/toode/only\">Only</a>"
        );

        assertThat(page.nextPageUrl()).isNull();
    }

    @Test
    void parsesProductCandidatesFromNextData() {
        String html = """
                <html><head><title>Nik Ar - Yaga</title></head><body>
                <script id="__NEXT_DATA__" type="application/json">
                {
                  "props": {
                    "pageProps": {
                      "products": [
                        {
                          "slug": "5u7arpkm6q",
                          "shop": {"activeSlug": "nik-ar"}
                        }
                      ]
                    }
                  }
                }
                </script>
                </body></html>
                """;

        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                html
        );

        assertThat(page.sourceIdentified()).isTrue();
        assertThat(page.productLinks())
                .extracting("productSlug")
                .containsExactly("5u7arpkm6q");
        assertThat(page.diagnostics().nextDataPresent()).isTrue();
        assertThat(page.diagnostics().pageTitle()).isEqualTo("Nik Ar - Yaga");
    }

    @Test
    void unknownEmptyLookingHtmlIsNotSuccessfulZeroResult() {
        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                "<html><head><title>Yaga</title></head><body><main></main></body></html>"
        );

        assertThat(page.sourceIdentified()).isFalse();
        assertThat(page.productLinks()).isEmpty();
        assertThat(page.diagnostics().anchorCount()).isZero();
    }

    @Test
    void confirmedEmptyShopIsSuccessfulZeroResult() {
        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                "<html><body>Tooteid ei leitud</body></html>"
        );

        assertThat(page.sourceIdentified()).isTrue();
        assertThat(page.confirmedEmpty()).isTrue();
        assertThat(page.productLinks()).isEmpty();
    }

    @Test
    void challengePageIsDiagnosticOnlyAndNotSuccessfulZeroResult() {
        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                "<html><title>Challenge</title><body>captcha challenge</body></html>"
        );

        assertThat(page.sourceIdentified()).isFalse();
        assertThat(page.diagnostics().challengeOrCaptchaDetected()).isTrue();
    }

    @Test
    void malformedNextDataDoesNotCountAsIdentifiedSource() {
        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                """
                        <script id="__NEXT_DATA__" type="application/json">
                        {"props":
                        </script>
                        """
        );

        assertThat(page.diagnostics().nextDataPresent()).isTrue();
        assertThat(page.sourceIdentified()).isFalse();
        assertThat(page.productLinks()).isEmpty();
    }

    @Test
    void buildShopPageUriUsesHttpsAndExpectedHost() {
        assertThat(client.buildShopPageUri("nik-ar", 1))
                .hasToString("https://www.yaga.ee/nik-ar");
        assertThat(client.buildShopPageUri("nik-ar", 3))
                .hasToString("https://www.yaga.ee/nik-ar?page=3");
    }
}
