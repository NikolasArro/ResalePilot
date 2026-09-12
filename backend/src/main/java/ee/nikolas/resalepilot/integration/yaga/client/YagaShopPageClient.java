package ee.nikolas.resalepilot.integration.yaga.client;

import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage.YagaShopPageDiagnostics;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YagaShopPageClient {

    public static final int DEFAULT_PRODUCT_API_LIMIT = 40;
    private static final int MAX_PRODUCT_API_LIMIT = 100;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_DIAGNOSTIC_EXAMPLES = 10;
    private static final int MAX_PAGINATION_FIELDS = 20;
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
        return getPage(shopSlug, pageNumber, uri, requestTimeout);
    }

    public YagaShopPage getContinuationPage(
            String shopSlug,
            int pageNumber,
            String nextPageUrl,
            Duration requestTimeout
    ) {
        URI uri;
        try {
            uri = URI.create(nextPageUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Yaga pagination URL is invalid",
                    exception
            );
        }
        validateYagaUri(uri);
        return getPage(shopSlug, pageNumber, uri, requestTimeout);
    }

    private YagaShopPage getPage(
            String shopSlug,
            int pageNumber,
            URI uri,
            Duration requestTimeout
    ) {
        HttpResponse<String> response = sendFollowingSafeRedirects(
                uri,
                requestTimeout,
                "text/html",
                this::validateYagaUri
        );

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

    public YagaShopPage getPublishedProductsPage(
            String shopSlug,
            long trustedShopId,
            int offset,
            int limit,
            Duration requestTimeout
    ) {
        URI uri = buildPublishedProductsUri(trustedShopId, offset, limit);
        validatePublishedProductsUri(uri, trustedShopId, limit);
        HttpResponse<String> response = sendFollowingSafeRedirects(
                uri,
                requestTimeout,
                "application/json",
                redirectUri -> validatePublishedProductsUri(
                        redirectUri,
                        trustedShopId,
                        limit
                )
        );
        validatePublishedProductsUri(response.uri(), trustedShopId, limit);
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Yaga published products API returned HTTP " +
                            response.statusCode()
            );
        }
        YagaShopPage page = parsePublishedProductsPage(
                shopSlug,
                trustedShopId,
                offset,
                limit,
                response.uri().toString(),
                response.statusCode(),
                response.headers().firstValue("content-type").orElse(null),
                response.body()
        );
        if (!page.sourceIdentified()) {
            throw new YagaShopListingSourceException(
                    "Yaga published products response is not a trusted product collection",
                    page.diagnostics()
            );
        }
        return page;
    }

    URI buildPublishedProductsUri(long trustedShopId, int offset, int limit) {
        if (trustedShopId <= 0 || offset < 0 || limit < 1 ||
                limit > MAX_PRODUCT_API_LIMIT) {
            throw new IllegalArgumentException(
                    "Invalid Yaga published products pagination parameters"
            );
        }
        return URI.create(
                "https://www.yaga.ee/api/product/?status=published" +
                        "&shopId=" + trustedShopId +
                        "&offset=" + offset +
                        "&limit=" + limit
        );
    }

    void validatePublishedProductsUri(
            URI uri,
            long trustedShopId,
            int expectedLimit
    ) {
        validateYagaUri(uri);
        if (!"/api/product/".equals(uri.getPath()) ||
                uri.getFragment() != null ||
                uri.getUserInfo() != null ||
                uri.getPort() != -1) {
            throw new IllegalStateException(
                    "Unsupported Yaga published products API path"
            );
        }
        Map<String, List<String>> query = parseQuery(uri.getRawQuery());
        if (!query.keySet().equals(Set.of(
                "shopId", "status", "offset", "limit"
        )) || query.values().stream().anyMatch(values -> values.size() != 1)) {
            throw new IllegalStateException(
                    "Unexpected Yaga published products API parameters"
            );
        }
        long shopId = parseLongParameter(query, "shopId");
        int offset = parseIntParameter(query, "offset");
        int limit = parseIntParameter(query, "limit");
        if (shopId != trustedShopId || trustedShopId <= 0 || offset < 0 ||
                expectedLimit < 1 || expectedLimit > MAX_PRODUCT_API_LIMIT ||
                limit != expectedLimit ||
                !"published".equals(query.get("status").getFirst())) {
            throw new IllegalStateException(
                    "Invalid Yaga published products API parameters"
            );
        }
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
        TrustedShopId trustedShopId = inspectTrustedShopId(shopSlug, html);
        PaginationInspection pagination = inspectPagination(
                shopSlug,
                pageNumber,
                html,
                productLinks.size()
        );
        String nextPageUrl = pagination.nextPageUrl();
        boolean confirmedEmpty = isConfirmedEmptyShop(html);
        boolean completenessConfirmed =
                confirmedEmpty ||
                        Boolean.FALSE.equals(pagination.hasNextPage()) &&
                                (pagination.declaredTotal() == null ||
                                        productLinks.size() ==
                                                pagination.declaredTotal()) ||
                        pagination.declaredTotalTrusted() &&
                                productLinks.size() ==
                                        pagination.declaredTotal();
        YagaShopPageDiagnostics diagnostics = diagnostics(
                shopSlug,
                requestedUrl,
                pageUrl,
                httpStatus,
                contentType,
                html,
                pagination.rawProductCount(),
                pagination,
                trustedShopId,
                completenessConfirmed
        );
        boolean sourceIdentified =
                trustedShopId.value() != null ||
                        !productLinks.isEmpty() ||
                        confirmedEmpty ||
                        diagnostics.productHrefCount() > 0;

        return new YagaShopPage(
                pageUrl,
                new ArrayList<>(productLinks.values()),
                nextPageUrl,
                completenessConfirmed,
                sourceIdentified,
                confirmedEmpty,
                trustedShopId.value(),
                diagnostics
        );
    }

    YagaShopPage parsePublishedProductsPage(
            String shopSlug,
            long trustedShopId,
            int offset,
            int limit,
            String pageUrl,
            int httpStatus,
            String contentType,
            String body
    ) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            root = null;
        }
        ApiProductCollection collection = findApiProductCollection(
                root,
                offset
        );
        List<YagaShopPage.ProductLink> links = new ArrayList<>();
        if (collection != null) {
            collectApiProductLinks(
                    shopSlug,
                    trustedShopId,
                    collection.array(),
                    links
            );
        }
        int rawProductCount = collection == null
                ? 0
                : collection.array().size();
        PaginationInspection pagination = apiPagination(collection);
        boolean sourceIdentified = collection != null;
        boolean confirmedEmpty = sourceIdentified && rawProductCount == 0;
        boolean completenessConfirmed = sourceIdentified &&
                rawProductCount < limit;
        TrustedShopId shopId = new TrustedShopId(
                trustedShopId,
                "request:/api/product/?shopId=<trusted>"
        );
        YagaShopPageDiagnostics diagnostics = diagnostics(
                shopSlug,
                pageUrl,
                pageUrl,
                httpStatus,
                contentType,
                body,
                rawProductCount,
                pagination,
                shopId,
                completenessConfirmed
        );
        return new YagaShopPage(
                pageUrl,
                links,
                null,
                completenessConfirmed,
                sourceIdentified,
                confirmedEmpty,
                trustedShopId,
                diagnostics
        );
    }

    private HttpResponse<String> sendFollowingSafeRedirects(
            URI initialUri,
            Duration requestTimeout,
            String accept,
            Consumer<URI> uriValidator
    ) {
        URI uri = initialUri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            uriValidator.accept(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("User-Agent", "Mozilla/5.0 ResalePilot/1.0")
                    .header("Accept", accept)
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
        for (String json : extractJsonBodies(html)) {
            try {
                JsonNode root = objectMapper.readTree(json);
                collectProductLinksFromJson(
                        expectedShopSlug,
                        root,
                        "$",
                        links
                );
            } catch (JacksonException ignored) {
                // Malformed embedded JSON is diagnostics-only; normal anchors may still work.
            }
        }
        return new ArrayList<>(links.values());
    }

    private void collectProductLinksFromJson(
            String expectedShopSlug,
            JsonNode node,
            String path,
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
                            path + "[]",
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
        if (expectedShopSlug.equals(shopSlug) &&
                isSafeSlug(productSlug) &&
                isPublishedListingContext(node, path)) {
            putProductLink(links, shopSlug, productSlug);
        }

        node.properties().forEach(entry ->
                collectProductLinksFromJson(
                        expectedShopSlug,
                        entry.getValue(),
                        path + "." + entry.getKey(),
                        links
                )
        );
    }

    private boolean isPublishedListingContext(JsonNode node, String path) {
        String status = text(node, "status");
        return (status == null || "published".equalsIgnoreCase(status)) &&
                !path.toLowerCase().contains("sold");
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

    private TrustedShopId inspectTrustedShopId(
            String expectedShopSlug,
            String body
    ) {
        Map<Long, String> ids = new LinkedHashMap<>();
        for (String json : extractJsonBodies(body)) {
            try {
                collectShopIds(
                        objectMapper.readTree(json),
                        "$",
                        expectedShopSlug,
                        ids
                );
            } catch (JacksonException ignored) {
                // The listing source diagnostics handle malformed JSON.
            }
        }
        if (ids.size() != 1) {
            return new TrustedShopId(null, null);
        }
        Map.Entry<Long, String> entry = ids.entrySet().iterator().next();
        return new TrustedShopId(entry.getKey(), entry.getValue());
    }

    private void collectShopIds(
            JsonNode node,
            String path,
            String expectedShopSlug,
            Map<Long, String> ids
    ) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                collectShopIds(
                        child,
                        path + "[" + index++ + "]",
                        expectedShopSlug,
                        ids
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String associatedSlug = text(node, "activeSlug");
        if (associatedSlug == null) {
            associatedSlug = text(node, "active_slug");
        }
        if (associatedSlug == null) {
            associatedSlug = shopSlug(node);
        }
        if (associatedSlug == null && isShopObjectPath(path)) {
            associatedSlug = text(node, "slug");
        }
        if (expectedShopSlug.equals(associatedSlug)) {
            addShopId(node, path, "shopId", ids);
            addShopId(node, path, "shop_id", ids);
            if (isShopObjectPath(path) || node.has("activeSlug") ||
                    node.has("active_slug")) {
                addShopId(node, path, "id", ids);
            }
        }

        node.properties().forEach(entry -> collectShopIds(
                entry.getValue(),
                path + "." + entry.getKey(),
                expectedShopSlug,
                ids
        ));
    }

    private void addShopId(
            JsonNode node,
            String path,
            String field,
            Map<Long, String> ids
    ) {
        Long id = positiveLong(node.get(field));
        if (id != null) {
            ids.putIfAbsent(id, path + "." + field);
        }
    }

    private boolean isShopObjectPath(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".shop") ||
                lower.endsWith(".seller") ||
                lower.endsWith(".store");
    }

    private Long positiveLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            long result = value.isIntegralNumber()
                    ? value.asLong()
                    : Long.parseLong(value.asString());
            return result > 0 ? result : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ApiProductCollection findApiProductCollection(
            JsonNode root,
            int requestedOffset
    ) {
        if (root == null || !root.isObject() ||
                !"success".equals(text(root, "status"))) {
            return null;
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            return null;
        }
        Integer responseOffset = nonNegativeInt(data.get("offset"));
        Integer total = nonNegativeInt(data.get("total"));
        JsonNode list = data.get("list");
        if (responseOffset == null || responseOffset != requestedOffset ||
                total == null || list == null || !list.isArray()) {
            return null;
        }
        return new ApiProductCollection(list, responseOffset, total);
    }

    private void collectApiProductLinks(
            String expectedShopSlug,
            long trustedShopId,
            JsonNode products,
            List<YagaShopPage.ProductLink> links
    ) {
        for (JsonNode rawItem : products) {
            if (rawItem == null || !rawItem.isObject()) {
                continue;
            }
            Long id = positiveIntegralLong(rawItem.get("id"));
            Long itemShopId = positiveIntegralLong(rawItem.get("shopId"));
            JsonNode shop = rawItem.get("shop");
            String itemShopSlug = shop != null && shop.isObject()
                    ? text(shop, "activeSlug")
                    : null;
            String slug = text(rawItem, "slug");
            if (id == null || itemShopId == null ||
                    itemShopId != trustedShopId ||
                    !expectedShopSlug.equals(itemShopSlug) ||
                    !isSafeSlug(slug) ||
                    !"published".equals(text(rawItem, "status")) ||
                    hasInactiveMarker(rawItem)) {
                continue;
            }
            Instant listedAt = parseInstant(text(rawItem, "listedAt"));
            JsonNode images = rawItem.get("images");
            int imageCount = images != null && images.isArray()
                    ? images.size()
                    : 0;
            links.add(new YagaShopPage.ProductLink(
                    expectedShopSlug,
                    slug,
                    "https://www.yaga.ee/" + expectedShopSlug +
                            "/toode/" + slug,
                    id,
                    listedAt,
                    imageCount
            )
            );
        }
    }

    private boolean hasInactiveMarker(JsonNode item) {
        for (String field : List.of("hiddenAt", "deletedAt")) {
            JsonNode value = item.get(field);
            if (value != null && !value.isNull()) {
                return true;
            }
        }
        for (String field : List.of("hidden", "deleted", "isHidden", "isDeleted")) {
            if (item.path(field).asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Integer nonNegativeInt(JsonNode value) {
        if (value == null || !value.isIntegralNumber() ||
                !value.canConvertToInt() || value.asInt() < 0) {
            return null;
        }
        return value.asInt();
    }

    private Long positiveIntegralLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() ||
                !value.canConvertToLong() || value.asLong() <= 0) {
            return null;
        }
        return value.asLong();
    }

    private PaginationInspection apiPagination(ApiProductCollection collection) {
        if (collection == null) {
            return PaginationInspection.empty();
        }
        return new PaginationInspection(
                collection.total(),
                "$.data.total",
                true,
                List.of(),
                List.of(
                        "$.data.offset=" + collection.offset(),
                        "$.data.total=" + collection.total()
                ),
                null,
                null,
                false,
                null,
                null,
                "confirmed:/api/product/",
                "$.data.list",
                List.of("/api/product/"),
                collection.array().size()
        );
    }

    private PaginationInspection inspectPagination(
            String shopSlug,
            int currentPage,
            String html,
            int candidateCount
    ) {
        PaginationCollector collector = new PaginationCollector();
        scriptSrcExamples(html).stream()
                .filter(route -> route != null && !route.isBlank())
                .forEach(route -> {
                    if (collector.routeNames.size() <
                            MAX_DIAGNOSTIC_EXAMPLES) {
                        collector.routeNames.add(route);
                    }
                });
        for (String json : extractJsonBodies(html)) {
            try {
                collectJsonDiagnostics(
                        objectMapper.readTree(json),
                        "$",
                        shopSlug,
                        collector
                );
            } catch (JacksonException ignored) {
                // Malformed JSON is reported by the surrounding diagnostics.
            }
        }

        Matcher matcher = PAGE_LINK_PATTERN.matcher(html);
        int nextPageNumber = Integer.MAX_VALUE;
        while (matcher.find()) {
            int page = Integer.parseInt(matcher.group(2));
            if (page > currentPage && page < nextPageNumber) {
                nextPageNumber = page;
            }
        }

        CandidatePagination trusted = collector.trustedCandidate();
        Integer declaredTotal = trusted == null
                ? null
                : trusted.declaredTotal;
        String declaredTotalSource = trusted == null
                ? null
                : trusted.declaredTotalSource;
        boolean declaredTotalTrusted = declaredTotalSource != null;
        if (declaredTotalTrusted && declaredTotal < candidateCount) {
            collector.reject(declaredTotalSource, declaredTotal);
            declaredTotal = null;
            declaredTotalSource = null;
            declaredTotalTrusted = false;
        }
        collector.rejectUntrustedTotals(declaredTotalSource);

        String nextPageUrl = trusted == null ? null : trusted.nextPageUrl;
        String continuationSource = trusted == null
                ? null
                : trusted.continuationSource;
        Boolean hasNextPage = trusted == null ? null : trusted.hasNextPage;
        String hasNextSource = trusted == null ? null : trusted.hasNextSource;
        if (nextPageNumber != Integer.MAX_VALUE) {
            nextPageUrl = buildShopPageUri(
                    shopSlug,
                    nextPageNumber
            ).toString();
            continuationSource = "html:a[href?page]";
        }

        if (nextPageUrl != null) {
            hasNextPage = true;
            if (hasNextSource == null) {
                hasNextSource = continuationSource;
            }
        }

        return new PaginationInspection(
                declaredTotal,
                declaredTotalSource,
                declaredTotalTrusted,
                List.copyOf(collector.rejectedTotals),
                List.copyOf(collector.fields),
                hasNextPage,
                hasNextSource,
                trusted != null && trusted.nextCursorAvailable,
                nextPageUrl,
                safeRequestPath(nextPageUrl),
                continuationSource,
                trusted == null ? null : trusted.candidateArraySource,
                List.copyOf(collector.routeNames),
                trusted == null ? candidateCount : trusted.rawArraySize
        );
    }

    private YagaShopPageDiagnostics diagnostics(
            String shopSlug,
            String requestedUrl,
            String finalUrl,
            int httpStatus,
            String contentType,
            String html,
            int initialItemCount,
            PaginationInspection pagination,
            TrustedShopId trustedShopId,
            boolean completenessConfirmed
    ) {
        String body = html == null ? "" : html;
        String lower = body.toLowerCase();
        List<String> productHrefExamples = productHrefExamples(body);
        List<String> scriptSrcExamples = scriptSrcExamples(body);
        String nextData = extractNextData(body);

        return new YagaShopPageDiagnostics(
                safeDiagnosticUrl(requestedUrl),
                safeDiagnosticUrl(finalUrl),
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
                body.contains(shopSlug),
                initialItemCount,
                pagination.declaredTotal(),
                pagination.declaredTotalSource(),
                pagination.declaredTotalTrusted(),
                pagination.rejectedTotalCandidates(),
                pagination.fields(),
                pagination.hasNextPage(),
                pagination.hasNextSource(),
                pagination.nextCursorAvailable(),
                pagination.nextRequestPath(),
                pagination.continuationSource(),
                pagination.candidateArraySource(),
                pagination.routeNames(),
                trustedShopId.value() != null,
                trustedShopId.source(),
                completenessConfirmed
        );
    }

    private List<String> productHrefExamples(String html) {
        Matcher matcher = HREF_PATTERN.matcher(html);
        Set<String> examples = new LinkedHashSet<>();
        while (matcher.find() && examples.size() < MAX_DIAGNOSTIC_EXAMPLES) {
            String href = matcher.group(1);
            if (href.contains("/toode/")) {
                String safeHref = safeRequestPath(href);
                if (safeHref != null) {
                    examples.add(safeHref);
                }
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
                String safeSrc = safeRequestPath(srcMatcher.group(1));
                if (safeSrc != null) {
                    examples.add(safeSrc);
                }
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

    private List<String> extractJsonBodies(String body) {
        List<String> result = new ArrayList<>();
        String value = body == null ? "" : body.trim();
        if (value.startsWith("{") || value.startsWith("[")) {
            result.add(value);
        }
        result.addAll(extractJsonScriptBodies(body));
        return result;
    }

    private void collectJsonDiagnostics(
            JsonNode node,
            String path,
            String shopSlug,
            PaginationCollector collector
    ) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addRelevantRoute(node.asString(), collector);
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                collectJsonDiagnostics(
                        child,
                        path + "[" + index++ + "]",
                        shopSlug,
                        collector
                );
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        node.properties().forEach(entry -> {
            String name = entry.getKey();
            String normalizedName = name.toLowerCase();
            JsonNode value = entry.getValue();
            String childPath = path + "." + name;

            if (isPotentialTotalField(normalizedName) &&
                    value.isIntegralNumber()) {
                int declaredTotal = value.asInt();
                if (declaredTotal >= 0) {
                    collector.totalCandidates.add(
                            new TotalCandidate(childPath, declaredTotal)
                    );
                }
                collector.addField(childPath + "=" + declaredTotal);
            } else if (isNumericPaginationField(normalizedName) &&
                    value.isIntegralNumber()) {
                collector.addField(childPath + "=" + value.asInt());
            }

            if (value.isArray()) {
                int directCandidates = directProductCandidateCount(
                        shopSlug,
                        value
                );
                if (directCandidates > 0 &&
                        isPublishedListingContext(node, path)) {
                    CandidatePagination candidate = new CandidatePagination(
                            childPath,
                            directCandidates,
                            value.size()
                    );
                    collectTrustedPagination(node, path, candidate, collector);
                    collector.candidates.add(candidate);
                }
            }

            collectJsonDiagnostics(
                    value,
                    childPath,
                    shopSlug,
                    collector
            );
        });
    }

    private int directProductCandidateCount(String shopSlug, JsonNode array) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (item.isObject()) {
                String slug = text(item, "slug");
                String itemShopSlug = shopSlug(item);
                if (shopSlug.equals(itemShopSlug) && isSafeSlug(slug) &&
                        isPublishedListingContext(item, "")) {
                    keys.add(itemShopSlug + "/" + slug);
                }
            } else if (item.isTextual()) {
                Matcher matcher = TEXT_PRODUCT_URL_PATTERN.matcher(
                        item.asString()
                );
                while (matcher.find()) {
                    if (shopSlug.equals(matcher.group(1)) &&
                            isSafeSlug(matcher.group(2))) {
                        keys.add(matcher.group(1) + "/" + matcher.group(2));
                    }
                }
            }
        }
        return keys.size();
    }

    private void collectTrustedPagination(
            JsonNode listingContext,
            String contextPath,
            CandidatePagination candidate,
            PaginationCollector collector
    ) {
        listingContext.properties().forEach(entry -> {
            String name = entry.getKey().toLowerCase();
            JsonNode value = entry.getValue();
            String source = contextPath + "." + entry.getKey();
            collectTrustedField(name, value, source, candidate, collector);
            if (isTrustedPaginationContainer(name) && value.isObject()) {
                value.properties().forEach(nested -> collectTrustedField(
                        nested.getKey().toLowerCase(),
                        nested.getValue(),
                        source + "." + nested.getKey(),
                        candidate,
                        collector
                ));
            }
        });
    }

    private void collectTrustedField(
            String name,
            JsonNode value,
            String source,
            CandidatePagination candidate,
            PaginationCollector collector
    ) {
        if (isPotentialTotalField(name) && value.isIntegralNumber() &&
                value.asInt() >= 0) {
            if (candidate.declaredTotal == null ||
                    value.asInt() > candidate.declaredTotal) {
                candidate.declaredTotal = value.asInt();
                candidate.declaredTotalSource = source;
            }
        }
        if ((name.equals("hasnext") || name.equals("hasnextpage"))) {
            Boolean hasNext = booleanValue(value);
            if (hasNext != null) {
                if (!Boolean.TRUE.equals(candidate.hasNextPage) || hasNext) {
                    candidate.hasNextPage = hasNext;
                    candidate.hasNextSource = source;
                }
                collector.addField(source + "=" + hasNext);
            }
        }
        if (isCursorField(name) && hasValue(value)) {
            candidate.nextCursorAvailable = true;
            collector.addField(source + "=<present>");
        }
        if (isNextUrlField(name) && value.isTextual()) {
            String safeUrl = safeYagaUrl(value.asString());
            if (safeUrl != null) {
                candidate.nextPageUrl = safeUrl;
                candidate.continuationSource = source;
                collector.addField(source + "=<safe-yaga-url>");
            }
        }
    }

    private boolean isPotentialTotalField(String name) {
        return name.equals("total") ||
                name.equals("count") ||
                name.equals("totalcount") ||
                name.equals("productcount") ||
                name.equals("listingcount") ||
                name.equals("totalproducts") ||
                name.equals("totallistings");
    }

    private boolean isNumericPaginationField(String name) {
        return name.equals("pagecount") ||
                name.equals("offset") ||
                name.equals("limit") ||
                name.equals("pagesize") ||
                name.equals("currentpage");
    }

    private boolean isTrustedPaginationContainer(String name) {
        return name.equals("pagination") ||
                name.equals("pageinfo") ||
                name.equals("page_info") ||
                name.equals("paging");
    }

    private boolean isCursorField(String name) {
        return name.equals("cursor") ||
                name.equals("nextcursor") ||
                name.equals("endcursor");
    }

    private boolean isNextUrlField(String name) {
        return name.equals("next") ||
                name.equals("nexturl") ||
                name.equals("nextpage") ||
                name.equals("nextpageurl");
    }

    private Boolean booleanValue(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            if ("true".equalsIgnoreCase(value.asString())) {
                return true;
            }
            if ("false".equalsIgnoreCase(value.asString())) {
                return false;
            }
        }
        return null;
    }

    private boolean hasValue(JsonNode value) {
        return value != null &&
                !value.isNull() &&
                (!value.isTextual() || !value.asString().isBlank());
    }

    private String safeYagaUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                uri = URI.create("https://www.yaga.ee").resolve(uri);
            }
            validateYagaUri(uri);
            return uri.toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void addRelevantRoute(
            String value,
            PaginationCollector collector
    ) {
        String lower = value.toLowerCase();
        if (!lower.contains("/api/") &&
                !lower.contains("/_next/data/") &&
                !lower.contains("graphql")) {
            return;
        }
        String route = safeRequestPath(value);
        if (route != null &&
                collector.routeNames.size() < MAX_DIAGNOSTIC_EXAMPLES) {
            collector.routeNames.add(route);
        }
    }

    private String safeRequestPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return path;
            }
            List<String> names = new ArrayList<>();
            for (String parameter : query.split("&")) {
                String name = parameter.split("=", 2)[0];
                if (!name.isBlank()) {
                    names.add(name + "=<redacted>");
                }
            }
            return names.isEmpty()
                    ? path
                    : path + "?" + String.join("&", names);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(
                    parts[0],
                    StandardCharsets.UTF_8
            );
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            parameters.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(value);
        }
        return parameters;
    }

    private long parseLongParameter(
            Map<String, List<String>> query,
            String name
    ) {
        try {
            return Long.parseLong(query.get(name).getFirst());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Invalid Yaga published products API parameter: " + name,
                    exception
            );
        }
    }

    private int parseIntParameter(
            Map<String, List<String>> query,
            String name
    ) {
        long value = parseLongParameter(query, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Yaga published products API parameter is out of range: " +
                            name
            );
        }
        return (int) value;
    }

    private String safeDiagnosticUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String safePath = safeRequestPath(value);
            if (safePath == null) {
                return null;
            }
            if (uri.getScheme() == null || uri.getHost() == null) {
                return safePath;
            }
            return uri.getScheme() + "://" + uri.getHost() + safePath;
        } catch (IllegalArgumentException exception) {
            return null;
        }
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

    private record PaginationInspection(
            Integer declaredTotal,
            String declaredTotalSource,
            boolean declaredTotalTrusted,
            List<String> rejectedTotalCandidates,
            List<String> fields,
            Boolean hasNextPage,
            String hasNextSource,
            boolean nextCursorAvailable,
            String nextPageUrl,
            String nextRequestPath,
            String continuationSource,
            String candidateArraySource,
            List<String> routeNames,
            int rawProductCount
    ) {
        private static PaginationInspection empty() {
            return new PaginationInspection(
                    null, null, false, List.of(), List.of(),
                    null, null, false, null, null, null, null,
                    List.of(), 0
            );
        }
    }

    private static class PaginationCollector {
        private final Set<String> fields = new LinkedHashSet<>();
        private final Set<String> routeNames = new LinkedHashSet<>();
        private final List<TotalCandidate> totalCandidates = new ArrayList<>();
        private final List<CandidatePagination> candidates = new ArrayList<>();
        private final Set<String> rejectedTotals = new LinkedHashSet<>();

        private void addField(String field) {
            if (fields.size() < MAX_PAGINATION_FIELDS) {
                fields.add(field);
            }
        }

        private CandidatePagination trustedCandidate() {
            CandidatePagination result = null;
            for (CandidatePagination candidate : candidates) {
                if (result == null ||
                        candidate.directCandidateCount >
                                result.directCandidateCount) {
                    result = candidate;
                }
            }
            return result;
        }

        private void rejectUntrustedTotals(String trustedSource) {
            for (TotalCandidate candidate : totalCandidates) {
                if (!candidate.source.equals(trustedSource)) {
                    reject(candidate.source, candidate.value);
                }
            }
        }

        private void reject(String source, int value) {
            if (rejectedTotals.size() < MAX_PAGINATION_FIELDS) {
                rejectedTotals.add(source + "=" + value);
            }
        }
    }

    private record TotalCandidate(String source, int value) {
    }

    private static class CandidatePagination {
        private final String candidateArraySource;
        private final int directCandidateCount;
        private final int rawArraySize;
        private Integer declaredTotal;
        private String declaredTotalSource;
        private Boolean hasNextPage;
        private String hasNextSource;
        private boolean nextCursorAvailable;
        private String nextPageUrl;
        private String continuationSource;

        private CandidatePagination(
                String candidateArraySource,
                int directCandidateCount,
                int rawArraySize
        ) {
            this.candidateArraySource = candidateArraySource;
            this.directCandidateCount = directCandidateCount;
            this.rawArraySize = rawArraySize;
        }
    }

    private record TrustedShopId(Long value, String source) {
    }

    private record ApiProductCollection(
            JsonNode array,
            int offset,
            int total
    ) {
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
