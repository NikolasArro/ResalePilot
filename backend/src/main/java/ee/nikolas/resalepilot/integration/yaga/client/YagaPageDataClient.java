package ee.nikolas.resalepilot.integration.yaga.client;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.parser.YagaPageDataParser;
import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.integration.yaga.exception.YagaImportException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YagaPageDataClient {

    private static final Pattern NEXT_DATA_PATTERN =
            Pattern.compile(
                    """
                    <script[^>]*id=["']__NEXT_DATA__["'][^>]*>\
                    (.*?)\
                    </script>
                    """,
                    Pattern.CASE_INSENSITIVE |
                            Pattern.DOTALL
            );

    private static final Pattern BUILD_ID_PATTERN =
            Pattern.compile(
                    "/_next/static/([^/\"']+)/_buildManifest\\.js"
            );

    private static final Pattern SLUG_PATTERN =
            Pattern.compile("[a-zA-Z0-9_-]+");

    private final HttpClient httpClient;
    private final YagaPageDataParser parser;

    public YagaPageDataClient(
            YagaPageDataParser parser
    ) {
        this.parser = parser;

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(
                        HttpClient.Redirect.NORMAL
                )
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public YagaImportedProductData getProduct(
            String productUrl
    ) {
        URI productUri = validateProductUrl(productUrl);

        String html = sendRequest(
                productUri,
                "text/html"
        );

        String pageData = findEmbeddedNextData(html);

        if (pageData == null) {
            pageData = loadNextData(
                    productUri,
                    html
            );
        }

        return parser.parse(pageData);
    }

    private String loadNextData(
            URI productUri,
            String html
    ) {
        String buildId = extractBuildId(html);
        ProductRoute route = extractProductRoute(productUri);

        String shopSlug =
                encodeQueryParameter(route.shopSlug());

        String productSlug =
                encodeQueryParameter(route.productSlug());

        String nextDataUrl = String.format(
                "https://www.yaga.ee/_next/data/%s/%s/toode/%s.json" +
                        "?shop-slug=%s&product-slug=%s",
                buildId,
                route.shopSlug(),
                route.productSlug(),
                shopSlug,
                productSlug
        );

        return sendRequest(
                URI.create(nextDataUrl),
                "application/json"
        );
    }

    private String sendRequest(
            URI uri,
            String accept
    ) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header(
                        "User-Agent",
                        "Mozilla/5.0 ResalePilot/1.0"
                )
                .header("Accept", accept)
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {
                throw new YagaImportException(
                        "Yaga returned HTTP " +
                                response.statusCode() +
                                " for " + uri.getPath()
                );
            }

            return response.body();

        } catch (IOException exception) {
            throw new YagaImportException(
                    "Failed to load Yaga page: " + uri,
                    exception
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new YagaImportException(
                    "Yaga request was interrupted",
                    exception
            );
        }
    }

    private String findEmbeddedNextData(String html) {
        Matcher matcher =
                NEXT_DATA_PATTERN.matcher(html);

        return matcher.find()
                ? matcher.group(1)
                : null;
    }

    private String extractBuildId(String html) {
        Matcher matcher =
                BUILD_ID_PATTERN.matcher(html);

        if (!matcher.find()) {
            throw new YagaImportException(
                    "Could not determine current Yaga buildId"
            );
        }

        return matcher.group(1);
    }

    private ProductRoute extractProductRoute(URI uri) {
        String[] parts = uri.getPath().split("/");

        /*
         * /nik-ar/toode/ip7p454fe6o
         *
         * 0 = ""
         * 1 = nik-ar
         * 2 = toode
         * 3 = ip7p454fe6o
         */
        if (parts.length != 4 ||
                !"toode".equals(parts[2]) ||
                !SLUG_PATTERN.matcher(parts[1]).matches() ||
                !SLUG_PATTERN.matcher(parts[3]).matches()) {

            throw new YagaImportException(
                    "Unsupported Yaga product URL path"
            );
        }

        return new ProductRoute(
                parts[1],
                parts[3]
        );
    }

    private URI validateProductUrl(String productUrl) {
        if (productUrl == null || productUrl.isBlank()) {
            throw new YagaImportException(
                    "Yaga product URL is required"
            );
        }

        URI uri;

        try {
            uri = URI.create(productUrl);
        } catch (IllegalArgumentException exception) {
            throw new YagaImportException(
                    "Invalid Yaga product URL",
                    exception
            );
        }

        String host = uri.getHost();

        boolean validHost =
                "yaga.ee".equalsIgnoreCase(host) ||
                        "www.yaga.ee".equalsIgnoreCase(host);

        boolean validScheme =
                "https".equalsIgnoreCase(uri.getScheme());

        if (!validHost || !validScheme) {
            throw new YagaImportException(
                    "URL must point to yaga.ee"
            );
        }

        extractProductRoute(uri);

        return uri;
    }

    private String encodeQueryParameter(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private record ProductRoute(
            String shopSlug,
            String productSlug
    ) {
    }
}