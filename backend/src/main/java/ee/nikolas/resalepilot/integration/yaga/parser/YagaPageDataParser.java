package ee.nikolas.resalepilot.integration.yaga.parser;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YagaPageDataParser {

    private static final Pattern JSON_LD_SCRIPT_PATTERN =
            Pattern.compile(
                    """
                    <script[^>]*type=["']application/ld\\+json["'][^>]*>\
                    (.*?)\
                    </script>
                    """,
                    Pattern.CASE_INSENSITIVE |
                            Pattern.DOTALL
            );

    private final ObjectMapper objectMapper;

    public YagaPageDataParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public YagaImportedProductData parse(String responseBody) {
        return parse(responseBody, null);
    }

    public YagaImportedProductData parse(
            String responseBody,
            String html
    ) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode product = findProductNode(root);
            String title = readProductTitle(
                    product,
                    html
            );

            return new YagaImportedProductData(
                    requiredLong(product, "id"),
                    readShopSlug(product),
                    requiredText(product, "slug"),
                    title,
                    nullableText(product, "description"),
                    requiredDecimal(product, "price"),
                    nullableText(product, "currency"),
                    nullableText(product, "status"),
                    readCondition(product),
                    readCategories(product),
                    readImages(product),
                    readInstant(product, "createdAt", "created_at"),
                    readInstant(product, "updatedAt", "updated_at"),
                    readInstant(product, "hiddenAt", "hidden_at"),
                    readInstant(product, "deletedAt", "deleted_at")
            );

        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Invalid Yaga page-data JSON",
                    exception
            );
        }
    }

    private String readProductTitle(
            JsonNode product,
            String html
    ) {
        String title = firstText(product, "title", "name");

        if (title != null && !title.isBlank()) {
            return title.trim();
        }

        title = readJsonLdProductName(html);

        return title == null || title.isBlank()
                ? null
                : title.trim();
    }

    private String readJsonLdProductName(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        Matcher matcher = JSON_LD_SCRIPT_PATTERN.matcher(html);

        while (matcher.find()) {
            String json = matcher.group(1);

            try {
                JsonNode node = objectMapper.readTree(json);
                String productName = findJsonLdProductName(node);

                if (productName != null &&
                        !productName.isBlank()) {
                    return productName;
                }
            } catch (JacksonException exception) {
                // Ignore malformed structured-data scripts; page-data remains authoritative.
            }
        }

        return null;
    }

    private String findJsonLdProductName(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                String result = findJsonLdProductName(child);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            }
            return null;
        }

        if (!node.isObject()) {
            return null;
        }

        if (isJsonLdType(node.path("@type"), "Product")) {
            String name = nullableText(node, "name");
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }

        for (JsonNode child : node) {
            String result = findJsonLdProductName(child);
            if (result != null && !result.isBlank()) {
                return result;
            }
        }

        return null;
    }

    private boolean isJsonLdType(JsonNode typeNode, String expectedType) {
        if (typeNode.isTextual()) {
            return expectedType.equalsIgnoreCase(typeNode.asString());
        }

        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() &&
                        expectedType.equalsIgnoreCase(item.asString())) {
                    return true;
                }
            }
        }

        return false;
    }

    private JsonNode findProductNode(JsonNode root) {
        JsonNode initialProduct = root
                .path("pageProps")
                .path("initialProduct");

        if (initialProduct.isObject()) {
            return initialProduct;
        }

        /*
         * Удобно для тестов и сохранённых ответов,
         * содержащих только initialProduct.
         */
        if (root.isObject() &&
                root.hasNonNull("id") &&
                root.hasNonNull("slug")) {
            return root;
        }

        throw new IllegalArgumentException(
                "Yaga response does not contain pageProps.initialProduct"
        );
    }

    private YagaImportedProductData.Condition readCondition(
            JsonNode product
    ) {
        JsonNode condition = product.path("condition");

        if (!condition.isObject()) {
            return null;
        }

        return new YagaImportedProductData.Condition(
                nullableLong(condition, "id"),
                nullableText(condition, "name")
        );
    }

    private List<YagaImportedProductData.Category> readCategories(
            JsonNode product
    ) {
        JsonNode categories = product.path("categories");

        if (!categories.isArray()) {
            return List.of();
        }

        List<YagaImportedProductData.Category> result =
                new ArrayList<>();

        for (JsonNode category : categories) {
            result.add(
                    new YagaImportedProductData.Category(
                            nullableLong(category, "id"),
                            nullableLong(category, "parent"),
                            nullableText(category, "title"),
                            readTextList(
                                    firstExisting(
                                            category,
                                            "enabledFields",
                                            "enabled_fields"
                                    )
                            )
                    )
            );
        }

        return List.copyOf(result);
    }

    private List<YagaImportedProductData.Image> readImages(
            JsonNode product
    ) {
        JsonNode images = product.path("images");

        if (!images.isArray()) {
            return List.of();
        }

        List<YagaImportedProductData.Image> result =
                new ArrayList<>();

        for (JsonNode image : images) {
            result.add(
                    new YagaImportedProductData.Image(
                            nullableText(image, "id"),
                            nullableText(image, "original"),
                            firstText(
                                    image,
                                    "fileName",
                                    "file_name"
                            )
                    )
            );
        }

        return List.copyOf(result);
    }

    private String readShopSlug(JsonNode product) {
        JsonNode shop = product.path("shop");

        return firstText(
                shop,
                "activeSlug",
                "active_slug"
        );
    }

    private List<String> readTextList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (JsonNode item : node) {
            if (item.isString()) {
                result.add(item.asString());
            }
        }

        return List.copyOf(result);
    }

    private JsonNode firstExisting(
            JsonNode node,
            String... fieldNames
    ) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);

            if (value != null && !value.isNull()) {
                return value;
            }
        }

        return null;
    }

    private String firstText(
            JsonNode node,
            String... fieldNames
    ) {
        JsonNode value = firstExisting(node, fieldNames);

        return value != null && value.isValueNode()
                ? value.asString()
                : null;
    }

    private String requiredText(
            JsonNode node,
            String fieldName
    ) {
        String value = nullableText(node, fieldName);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required Yaga field is missing: " + fieldName
            );
        }

        return value;
    }

    private String nullableText(
            JsonNode node,
            String fieldName
    ) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull()
                ? null
                : value.asString();
    }

    private Long requiredLong(
            JsonNode node,
            String fieldName
    ) {
        Long value = nullableLong(node, fieldName);

        if (value == null) {
            throw new IllegalArgumentException(
                    "Required Yaga field is missing: " + fieldName
            );
        }

        return value;
    }

    private Long nullableLong(
            JsonNode node,
            String fieldName
    ) {
        JsonNode value = node.get(fieldName);

        return value == null ||
                value.isNull() ||
                !value.canConvertToLong()
                ? null
                : value.longValue();
    }

    private BigDecimal requiredDecimal(
            JsonNode node,
            String fieldName
    ) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "Required Yaga field is missing: " + fieldName
            );
        }

        return value.decimalValue();
    }

    private Instant readInstant(
            JsonNode node,
            String... fieldNames
    ) {
        String value = firstText(node, fieldNames);

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Invalid Yaga timestamp: " + value,
                    exception
            );
        }
    }
}
