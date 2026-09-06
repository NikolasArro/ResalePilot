package ee.nikolas.resalepilot.integration.yaga.client;

import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage.YagaShopPageDiagnostics;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YagaShopPageClient {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_DIAGNOSTIC_EXAMPLES = 10;
    private static final Pattern PRODUCT_LINK_PATTERN =
            Pattern.compile(
                    "href=[\"'](/([^/\"'?#]+)/toode/([^/\"'?#]+))[\"']",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern PAGE_LINK_PATTERN =
            Pattern.compile(
                    "href=[\"']([^\"']*[?&]page=(\\d+)[^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern ANCHOR_PATTERN =
            Pattern.compile("<a\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN =
            Pattern.compile(
                    "<title[^>]*>(.*?)</title>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile(
                    "<script\\b([^>]*)>(.*?)</script>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
    private static final Pattern SCRIPT_SRC_PATTERN =
            Pattern.compile("src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_DATA_PATTERN =
            Pattern.compile(
                    "<script\\b[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
    private static final Pattern SCRIPT_TYPE_JSON_PATTERN =
            Pattern.compile(
                    "type=[\"'](?:application/json|application/ld\\+json)[\"']",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern TEXT_PRODUCT_URL_PATTERN =
            Pattern.compile(
                    "(?:https://(?:www\\.)?yaga\\.ee)?/([^/\"'\\s?#]+)/toode/([^/\"'\\s?#]+)",
                    Pattern.CASE_INSENSITIVE
            );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public YagaShopPageClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public YagaShopPage getPage(
            String shopSlug,
            int pageNumber,
            Duration requestTimeout
    ) {
        URI uri = buildShopPageUri(shopSlug, pageNumber);
        HttpResponse<String> response =
                sendFollowingSafeRedirects(uri, requestTimeout);

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Yaga shop returned HTTP " + response.statusCode()
            );
        }

        YagaShopPage page = parseShopPage(
                shopSlug,
                pageNumber,
                uri.toString(),
                response.uri().toString(),
                response.statusCode(),
                response.headers()
                        .firstValue("content-type")
                        .orElse(null),
                response.body()
        );

        if (!page.sourceIdentified()) {
            throw new YagaShopListingSourceException(
                    "Yaga shop listing source could not be identified",
                    page.diagnostics()
            );
        }

        return page;
    }

    URI buildShopPageUri(String shopSlug, int pageNumber) {
        String encodedShopSlug = encodePathSegment(shopSlug);
        if (pageNumber <= 1) {
            return URI.create("https://www.yaga.ee/" + encodedShopSlug);
        }
        return URI.create(
                "https://www.yaga.ee/" +
                        encodedShopSlug +
                        "?page=" +
                        pageNumber
        );
    }

    YagaShopPage parseShopPage(
            String shopSlug,
            int pageNumber,
            String requestedUrl,
            String pageUrl,
            int httpStatus,
            String contentType,
            String html
    ) {
        Map<String, YagaShopPage.ProductLink> productLinks =
                new LinkedHashMap<>();
        addProductLinks(productLinks, extractProductLinksFromAnchors(shopSlug, html));
        addProductLinks(productLinks, extractProductLinksFromJsonScripts(shopSlug, html));
        String nextPageUrl = extractNextPageUrl(
                shopSlug,
                pageNumber,
                html
        );
        YagaShopPageDiagnostics diagnostics = diagnostics(
                shopSlug,
                requestedUrl,
                pageUrl,
                httpStatus,
                contentType,
                html
        );
        boolean confirmedEmpty = isConfirmedEmptyShop(html);
        boolean sourceIdentified =
                !productLinks.isEmpty() ||
                        confirmedEmpty ||
                        diagnostics.productHrefCount() > 0;

        return new YagaShopPage(
                pageUrl,
                new ArrayList<>(productLinks.values()),
                nextPageUrl,
                sourceIdentified,
                confirmedEmpty,
                diagnostics
        );
    }

    private HttpResponse<String> sendFollowingSafeRedirects(
            URI initialUri,
            Duration requestTimeout
    ) {
        URI uri = initialUri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("User-Agent", "Mozilla/5.0 ResalePilot/1.0")
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (!isRedirect(response.statusCode())) {
                    return response;
                }

                String location = response.headers()
                        .firstValue("location")
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Yaga redirect without Location"
                                )
                        );
                URI redirectUri = uri.resolve(location);
                validateYagaUri(redirectUri);
                uri = redirectUri;

            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to load Yaga shop page",
                        exception
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Yaga shop request was interrupted",
                        exception
                );
            }
        }

        throw new IllegalStateException("Too many Yaga shop redirects");
    }

    private List<YagaShopPage.ProductLink> extractProductLinksFromAnchors(
            String expectedShopSlug,
            String html
    ) {
        Matcher matcher = PRODUCT_LINK_PATTERN.matcher(html);
        Map<String, YagaShopPage.ProductLink> links =
                new LinkedHashMap<>();

        while (matcher.find()) {
            String shopSlug = matcher.group(2);
            String productSlug = matcher.group(3);
            if (!expectedShopSlug.equals(shopSlug) ||
                    !isSafeSlug(productSlug)) {
                continue;
            }

            links.putIfAbsent(
                    shopSlug + "/" + productSlug,
                    new YagaShopPage.ProductLink(
                            shopSlug,
                            productSlug,
                            "https://www.yaga.ee/" +
                                    shopSlug +
                                    "/toode/" +
                                    productSlug
                    )
            );
        }

        return new ArrayList<>(links.values());
    }

    private List<YagaShopPage.ProductLink> extractProductLinksFromJsonScripts(
            String expectedShopSlug,
            String html
    ) {
        Map<String, YagaShopPage.ProductLink> links =
                new LinkedHashMap<>();
        for (String json : extractJsonScriptBodies(html)) {
            try {
                JsonNode root = objectMapper.readTree(json);
                collectProductLinksFromJson(expectedShopSlug, root, links);
            } catch (JacksonException ignored) {
                // Malformed embedded JSON is diagnostics-only; normal anchors may still work.
            }
        }
        return new ArrayList<>(links.values());
    }

    private void collectProductLinksFromJson(
            String expectedShopSlug,
            JsonNode node,
            Map<String, YagaShopPage.ProductLink> links
    ) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addProductLinksFromText(expectedShopSlug, node.asString(), links);
            return;
        }
        if (node.isArray()) {
            node.forEach(child ->
                    collectProductLinksFromJson(
                            expectedShopSlug,
                            child,
                            links
                    )
            );
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String productSlug = text(node, "slug");
        String shopSlug = shopSlug(node);
        if (expectedShopSlug.equals(shopSlug) && isSafeSlug(productSlug)) {
            putProductLink(links, shopSlug, productSlug);
        }

        node.properties().forEach(entry ->
                collectProductLinksFromJson(
                        expectedShopSlug,
                        entry.getValue(),
                        links
                )
        );
    }

    private void addProductLinksFromText(
            String expectedShopSlug,
            String text,
            Map<String, YagaShopPage.ProductLink> links
    ) {
        Matcher matcher = TEXT_PRODUCT_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String shopSlug = matcher.group(1);
            String productSlug = matcher.group(2);
            if (expectedShopSlug.equals(shopSlug) && isSafeSlug(productSlug)) {
                putProductLink(links, shopSlug, productSlug);
            }
        }
    }

    private void putProductLink(
            Map<String, YagaShopPage.ProductLink> links,
            String shopSlug,
            String productSlug
    ) {
        links.putIfAbsent(
                shopSlug + "/" + productSlug,
                new YagaShopPage.ProductLink(
                        shopSlug,
                        productSlug,
                        "https://www.yaga.ee/" +
                                shopSlug +
                                "/toode/" +
                                productSlug
                )
        );
    }

    private void addProductLinks(
            Map<String, YagaShopPage.ProductLink> target,
            List<YagaShopPage.ProductLink> links
    ) {
        links.forEach(link ->
                target.putIfAbsent(
                        link.shopSlug() + "/" + link.productSlug(),
                        link
                )
        );
    }

    private String extractNextPageUrl(
            String shopSlug,
            int currentPage,
            String html
    ) {
        Matcher matcher = PAGE_LINK_PATTERN.matcher(html);
        int nextPageNumber = Integer.MAX_VALUE;
        while (matcher.find()) {
            int page = Integer.parseInt(matcher.group(2));
            if (page > currentPage && page < nextPageNumber) {
                nextPageNumber = page;
            }
        }

        if (nextPageNumber == Integer.MAX_VALUE) {
            return null;
        }

        return buildShopPageUri(shopSlug, nextPageNumber).toString();
    }

    private YagaShopPageDiagnostics diagnostics(
            String shopSlug,
            String requestedUrl,
            String finalUrl,
            int httpStatus,
            String contentType,
            String html
    ) {
        String body = html == null ? "" : html;
        String lower = body.toLowerCase();
        List<String> productHrefExamples = productHrefExamples(body);
        List<String> scriptSrcExamples = scriptSrcExamples(body);
        String nextData = extractNextData(body);

        return new YagaShopPageDiagnostics(
                requestedUrl,
                finalUrl,
                httpStatus,
                contentType,
                body.length(),
                pageTitle(body),
                nextData != null,
                nextData == null ? 0 : nextData.length(),
                countMatches(ANCHOR_PATTERN, body),
                countProductHrefs(body),
                productHrefExamples,
                countMatches(SCRIPT_PATTERN, body),
                scriptSrcExamples,
                containsAny(lower, "logi sisse", "login", "sign in"),
                containsAny(lower, "captcha", "challenge", "cloudflare"),
                containsAny(lower, "access denied", "juurdepääs keelatud"),
                body.contains(shopSlug)
        );
    }

    private List<String> productHrefExamples(String html) {
        Matcher matcher = HREF_PATTERN.matcher(html);
        Set<String> examples = new LinkedHashSet<>();
        while (matcher.find() && examples.size() < MAX_DIAGNOSTIC_EXAMPLES) {
            String href = matcher.group(1);
            if (href.contains("/toode/")) {
                examples.add(href);
            }
        }
        return List.copyOf(examples);
    }

    private int countProductHrefs(String html) {
        Matcher matcher = HREF_PATTERN.matcher(html);
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(1).contains("/toode/")) {
                count++;
            }
        }
        return count;
    }

    private List<String> scriptSrcExamples(String html) {
        Matcher scriptMatcher = SCRIPT_PATTERN.matcher(html);
        Set<String> examples = new LinkedHashSet<>();
        while (scriptMatcher.find() &&
                examples.size() < MAX_DIAGNOSTIC_EXAMPLES) {
            Matcher srcMatcher = SCRIPT_SRC_PATTERN.matcher(
                    scriptMatcher.group(1)
            );
            if (srcMatcher.find()) {
                examples.add(srcMatcher.group(1));
            }
        }
        return List.copyOf(examples);
    }

    private List<String> extractJsonScriptBodies(String html) {
        List<String> result = new ArrayList<>();
        String nextData = extractNextData(html);
        if (nextData != null && !nextData.isBlank()) {
            result.add(nextData);
        }

        Matcher matcher = SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String body = matcher.group(2);
            if (SCRIPT_TYPE_JSON_PATTERN.matcher(attributes).find() &&
                    body != null &&
                    !body.isBlank()) {
                result.add(body);
            }
        }
        return result;
    }

    private String extractNextData(String html) {
        Matcher matcher = NEXT_DATA_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String pageTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        return matcher.find()
                ? matcher.group(1).replaceAll("\\s+", " ").trim()
                : null;
    }

    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isConfirmedEmptyShop(String html) {
        String lower = html == null ? "" : html.toLowerCase();
        return containsAny(
                lower,
                "tooteid ei leitud",
                "müügis pole tooteid",
                "pole ühtegi toodet",
                "no products",
                "no listings"
        );
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() ? value.asString() : null;
    }

    private String shopSlug(JsonNode node) {
        JsonNode shop = node.get("shop");
        if (shop != null && shop.isObject()) {
            String activeSlug = text(shop, "activeSlug");
            if (activeSlug != null) {
                return activeSlug;
            }
            return text(shop, "active_slug");
        }
        return text(node, "shopSlug");
    }

    private void validateYagaUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                (!"www.yaga.ee".equalsIgnoreCase(host) &&
                        !"yaga.ee".equalsIgnoreCase(host))) {
            throw new IllegalStateException(
                    "Yaga shop redirected to unsupported host"
            );
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 ||
                statusCode == 302 ||
                statusCode == 303 ||
                statusCode == 307 ||
                statusCode == 308;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private boolean isSafeSlug(String value) {
        return value != null &&
                value.matches("[a-zA-Z0-9_-]+");
    }

    public static class YagaShopListingSourceException
            extends RuntimeException {

        private final YagaShopPageDiagnostics diagnostics;

        public YagaShopListingSourceException(
                String message,
                YagaShopPageDiagnostics diagnostics
        ) {
            super(message);
            this.diagnostics = diagnostics;
        }

        public YagaShopPageDiagnostics diagnostics() {
            return diagnostics;
        }
    }
}
