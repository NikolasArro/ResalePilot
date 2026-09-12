package ee.nikolas.resalepilot.integration.yaga.client;

import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(page.completenessConfirmed()).isFalse();
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
    void extractsSafePaginationDiagnosticsFromInitialNextData() {
        String products = IntStream.range(0, 32)
                .mapToObj(index -> """
                        {"slug":"item-%d","shop":{"activeSlug":"nik-ar"}}
                        """.formatted(index))
                .collect(Collectors.joining(","));
        String html = """
                <script src="/_next/static/chunks/shop.js?token=secret"></script>
                <script id="__NEXT_DATA__" type="application/json">
                {
                  "props": {"pageProps": {"products": {
                    "items": [%s],
                    "totalCount": 60,
                    "pageInfo": {
                      "hasNextPage": true,
                      "endCursor": "opaque-cursor",
                      "nextUrl": "/api/products?cursor=opaque-cursor&limit=32"
                    }
                  }}}
                }
                </script>
                """.formatted(products);

        var page = client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                html
        );

        assertThat(page.productLinks()).hasSize(32);
        assertThat(page.nextPageUrl())
                .isEqualTo("https://www.yaga.ee/api/products" +
                        "?cursor=opaque-cursor&limit=32");
        assertThat(page.completenessConfirmed()).isFalse();
        assertThat(page.diagnostics().initialItemCount()).isEqualTo(32);
        assertThat(page.diagnostics().declaredTotal()).isEqualTo(60);
        assertThat(page.diagnostics().declaredTotalSource())
                .isEqualTo("$.props.pageProps.products.totalCount");
        assertThat(page.diagnostics().declaredTotalTrusted()).isTrue();
        assertThat(page.diagnostics().candidateArraySource())
                .isEqualTo("$.props.pageProps.products.items");
        assertThat(page.diagnostics().hasNextPage()).isTrue();
        assertThat(page.diagnostics().hasNextSource())
                .isEqualTo("$.props.pageProps.products.pageInfo.hasNextPage");
        assertThat(page.diagnostics().nextCursorAvailable()).isTrue();
        assertThat(page.diagnostics().nextRequestPath())
                .isEqualTo("/api/products?cursor=<redacted>&limit=<redacted>");
        assertThat(page.diagnostics().continuationSource())
                .isEqualTo("$.props.pageProps.products.pageInfo.nextUrl");
        assertThat(page.diagnostics().relevantRouteNames())
                .contains("/_next/static/chunks/shop.js?token=<redacted>");
        assertThat(page.diagnostics().paginationFields())
                .anyMatch(field -> field.endsWith("totalCount=60"))
                .anyMatch(field -> field.endsWith("hasNextPage=true"))
                .anyMatch(field -> field.endsWith("endCursor=<present>"));
    }

    @Test
    void unrelatedTotalAndHasNextDoNotConfirmListingEnd() {
        String html = """
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{
                  "products":{"items":[%s]},
                  "sidebar":{"results":{"total":9,"hasNext":false}}
                }}}
                </script>
                """.formatted(products(32));

        var page = parse(html);

        assertThat(page.productLinks()).hasSize(32);
        assertThat(page.completenessConfirmed()).isFalse();
        assertThat(page.diagnostics().declaredTotal()).isNull();
        assertThat(page.diagnostics().declaredTotalTrusted()).isFalse();
        assertThat(page.diagnostics().rejectedTotalCandidates())
                .contains("$.props.pageProps.sidebar.results.total=9");
        assertThat(page.diagnostics().hasNextPage()).isNull();
        assertThat(page.diagnostics().hasNextSource()).isNull();
        assertThat(page.diagnostics().candidateArraySource())
                .isEqualTo("$.props.pageProps.products.items");
    }

    @Test
    void trustedTotalEqualToUniqueCandidatesConfirmsEnd() {
        var page = parse("""
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"products":{
                  "items":[%s],"totalCount":32
                }}}}
                </script>
                """.formatted(products(32)));

        assertThat(page.completenessConfirmed()).isTrue();
        assertThat(page.diagnostics().declaredTotal()).isEqualTo(32);
        assertThat(page.diagnostics().declaredTotalTrusted()).isTrue();
    }

    @Test
    void duplicateCandidatesCannotSatisfyTrustedTotal() {
        var page = parse("""
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"products":{
                  "items":[
                    {"slug":"same","shop":{"activeSlug":"nik-ar"}},
                    {"slug":"same","shop":{"activeSlug":"nik-ar"}}
                  ],"totalCount":2
                }}}}
                </script>
                """);

        assertThat(page.productLinks()).hasSize(1);
        assertThat(page.completenessConfirmed()).isFalse();
        assertThat(page.diagnostics().declaredTotal()).isEqualTo(2);
    }

    @Test
    void explicitHasNextFalseConfirmsEnd() {
        String html = """
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"products":{
                  "items":[
                    {"slug":"last","shop":{"activeSlug":"nik-ar"}}
                  ],
                  "pageInfo":{"hasNextPage":false}
                }}}}
                </script>
                """;

        var page = client.parseShopPage(
                "nik-ar",
                2,
                "https://www.yaga.ee/nik-ar?page=2",
                "https://www.yaga.ee/nik-ar?page=2",
                200,
                "text/html",
                html
        );

        assertThat(page.nextPageUrl()).isNull();
        assertThat(page.completenessConfirmed()).isTrue();
        assertThat(page.diagnostics().hasNextPage()).isFalse();
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

    @Test
    void extractsShopIdOnlyFromExpectedShopAssociation() {
        var trusted = parse("""
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"products":[
                  {"slug":"one","shop":{"id":8413833,"activeSlug":"nik-ar"}}
                ]}}}
                </script>
                """);
        var wrongShop = parse("""
                <a href="/nik-ar/toode/one">One</a>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"shop":{
                  "id":8413833,"activeSlug":"other-shop"
                }}}}
                </script>
                """);

        assertThat(trusted.trustedShopId()).isEqualTo(8413833L);
        assertThat(trusted.diagnostics().trustedShopIdFound()).isTrue();
        assertThat(trusted.diagnostics().trustedShopIdSource())
                .endsWith(".shop.id");
        assertThat(wrongShop.trustedShopId()).isNull();
        assertThat(wrongShop.diagnostics().trustedShopIdFound()).isFalse();
    }

    @Test
    void trustedShopIdentityIsEnoughForApiBootstrapWithoutHtmlProducts() {
        var page = parse("""
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"shop":{
                  "id":8413833,"activeSlug":"nik-ar"
                }}}}
                </script>
                """);

        assertThat(page.sourceIdentified()).isTrue();
        assertThat(page.trustedShopId()).isEqualTo(8413833L);
        assertThat(page.productLinks()).isEmpty();
    }

    @Test
    void publishedProductApiUrlIsStrictlyBuiltAndValidated() {
        var uri = client.buildPublishedProductsUri(8413833L, 0, 40);

        assertThat(uri).hasToString(
                "https://www.yaga.ee/api/product/" +
                        "?status=published&shopId=8413833&offset=0&limit=40"
        );
        client.validatePublishedProductsUri(uri, 8413833L, 40);

        var sixty = client.buildPublishedProductsUri(8413833L, 60, 60);
        client.validatePublishedProductsUri(sixty, 8413833L, 60);

        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://evil.example/api/product/?shopId=8413833" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "http://www.yaga.ee/api/product/?shopId=8413833" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee:8443/api/product/?shopId=8413833" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/products/?shopId=8413833" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/product/?shopId=8413833" +
                                "&status=sold&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/product/?shopId=8413833" +
                                "&status=published&status=published" +
                                "&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/product/?shopId=8413833" +
                                "&status=published&offset=32&limit=32&extra=x"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/product/?shopId=99" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                32
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.validatePublishedProductsUri(
                java.net.URI.create(
                        "https://www.yaga.ee/api/product/?shopId=8413833" +
                                "&status=published&offset=32&limit=32"
                ),
                8413833L,
                40
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                client.buildPublishedProductsUri(8413833L, -1, 40)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                client.buildPublishedProductsUri(8413833L, 32, 101)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void soldPaginationMetadataIsNotPublishedPagination() {
        var page = parse("""
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{
                  "products":{"items":[
                    {"slug":"active","status":"published",
                     "shop":{"id":8413833,"activeSlug":"nik-ar"}}
                  ]},
                  "sold":{"status":"sold","products":[],
                    "total":99,"pageInfo":{"hasNextPage":false}}
                }}}
                </script>
                """);

        assertThat(page.productLinks())
                .extracting("productSlug")
                .containsExactly("active");
        assertThat(page.diagnostics().hasNextPage()).isNull();
        assertThat(page.completenessConfirmed()).isFalse();
        assertThat(page.trustedShopId()).isEqualTo(8413833L);
    }

    @Test
    void parsesOnlyConfirmedPublishedApiProductCollection() {
        var page = client.parsePublishedProductsPage(
                "nik-ar",
                8413833L,
                40,
                40,
                "https://www.yaga.ee/api/product/?shopId=8413833" +
                        "&status=published&offset=40&limit=40",
                200,
                "application/json",
                """
                        {"status":"success","data":{
                          "offset":40,"total":63,"list":[
                            {"id":20974812,"shopId":8413833,
                             "slug":"active","status":"published",
                             "listedAt":"2025-10-09T19:44:38.628Z",
                             "shop":{"activeSlug":"nik-ar"},
                             "images":[{"url":"secret-1"},{"url":"secret-2"}]},
                            {"id":2,"shopId":8413833,"slug":"sold",
                             "status":"sold","shop":{"activeSlug":"nik-ar"}},
                            {"id":3,"shopId":99,"slug":"wrong-id",
                             "status":"published","shop":{"activeSlug":"nik-ar"}},
                            {"id":4,"shopId":8413833,"slug":"wrong-slug",
                             "status":"published","shop":{"activeSlug":"other"}},
                            {"id":5,"shopId":8413833,"slug":"hidden",
                             "status":"published","hiddenAt":"2025-01-01T00:00:00Z",
                             "shop":{"activeSlug":"nik-ar"}}
                          ]
                        }}
                        """
        );

        assertThat(page.sourceIdentified()).isTrue();
        assertThat(page.diagnostics().initialItemCount()).isEqualTo(5);
        assertThat(page.productLinks())
                .extracting("productSlug")
                .containsExactly("active");
        assertThat(page.productLinks().getFirst().externalListingId())
                .isEqualTo(20974812L);
        assertThat(page.productLinks().getFirst().externalCreatedAt())
                .isEqualTo(java.time.Instant.parse(
                        "2025-10-09T19:44:38.628Z"
                ));
        assertThat(page.productLinks().getFirst().imageCount()).isEqualTo(2);
        assertThat(page.productLinks().getFirst().publicUrl()).isEqualTo(
                "https://www.yaga.ee/nik-ar/toode/active"
        );
        assertThat(page.completenessConfirmed()).isTrue();
        assertThat(page.diagnostics().declaredTotal()).isEqualTo(63);
        assertThat(page.diagnostics().declaredTotalSource())
                .isEqualTo("$.data.total");
        assertThat(page.diagnostics().declaredTotalTrusted()).isTrue();
        assertThat(page.diagnostics().rejectedTotalCandidates()).isEmpty();
        assertThat(page.diagnostics().candidateArraySource())
                .isEqualTo("$.data.list");
    }

    @Test
    void rejectsApiEnvelopeContractViolations() {
        assertUntrustedApi("""
                {"status":"error","data":{"offset":40,"total":63,"list":[]}}
                """, 40);
        assertUntrustedApi("""
                {"status":"success","data":{"offset":40,"total":63}}
                """, 40);
        assertUntrustedApi("""
                {"status":"success","data":{"offset":40,"total":63,"list":{}}}
                """, 40);
        assertUntrustedApi("""
                {"status":"success","data":{"offset":0,"total":63,"list":[]}}
                """, 40);
        assertUntrustedApi("""
                {"status":"success","data":{"offset":"40","total":63,"list":[]}}
                """, 40);
        assertUntrustedApi("""
                {"status":"success","data":{"offset":40,"total":-1,"list":[]}}
                """, 40);
        assertUntrustedApi("""
                {"data":{"offset":40,"total":63,"list":[]}}
                """, 40);
    }

    @Test
    void rejectsApiItemsMissingRequiredIdentity() {
        var page = apiPage("""
                {"status":"success","data":{"offset":0,"total":5,"list":[
                  {"shopId":8413833,"slug":"missing-id","status":"published",
                   "shop":{"activeSlug":"nik-ar"}},
                  {"id":2,"shopId":8413833,"status":"published",
                   "shop":{"activeSlug":"nik-ar"}},
                  {"id":3,"shopId":8413833,"slug":"wrong-shop",
                   "status":"published","shop":{"activeSlug":"other"}},
                  {"id":4,"shopId":99,"slug":"wrong-id","status":"published",
                   "shop":{"activeSlug":"nik-ar"}},
                  {"id":5,"shopId":8413833,"slug":"upper-status",
                   "status":"Published","shop":{"activeSlug":"nik-ar"}},
                  {"id":"6","shopId":8413833,"slug":"text-id",
                   "status":"published","shop":{"activeSlug":"nik-ar"}}
                ]}}
                """, 0);

        assertThat(page.sourceIdentified()).isTrue();
        assertThat(page.productLinks()).isEmpty();
        assertThat(page.diagnostics().initialItemCount()).isEqualTo(6);
    }

    @Test
    void malformedOrUnknownApiJsonIsNotAConfirmedEnd() {
        var malformed = client.parsePublishedProductsPage(
                "nik-ar", 8413833L, 32, 32,
                "https://www.yaga.ee/api/product/", 200,
                "application/json", "{broken"
        );
        var unknown = client.parsePublishedProductsPage(
                "nik-ar", 8413833L, 32, 32,
                "https://www.yaga.ee/api/product/", 200,
                "application/json", "{\"widgets\":[]}"
        );

        assertThat(malformed.sourceIdentified()).isFalse();
        assertThat(malformed.completenessConfirmed()).isFalse();
        assertThat(unknown.sourceIdentified()).isFalse();
        assertThat(unknown.completenessConfirmed()).isFalse();
    }

    private YagaShopPage apiPage(String body, int offset) {
        return client.parsePublishedProductsPage(
                "nik-ar", 8413833L, offset, 40,
                "https://www.yaga.ee/api/product/", 200,
                "application/json", body
        );
    }

    private void assertUntrustedApi(String body, int offset) {
        var page = apiPage(body, offset);
        assertThat(page.sourceIdentified()).isFalse();
        assertThat(page.completenessConfirmed()).isFalse();
        assertThat(page.diagnostics().declaredTotalTrusted()).isFalse();
        assertThat(page.diagnostics().candidateArraySource()).isNull();
    }

    private YagaShopPage parse(String html) {
        return client.parseShopPage(
                "nik-ar",
                1,
                "https://www.yaga.ee/nik-ar",
                "https://www.yaga.ee/nik-ar",
                200,
                "text/html",
                html
        );
    }

    private String products(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> """
                        {"slug":"item-%d","shop":{"activeSlug":"nik-ar"}}
                        """.formatted(index))
                .collect(Collectors.joining(","));
    }
}
