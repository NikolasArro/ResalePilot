package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;

import static ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence.Outcome.*;

final class YagaPublicationResponseParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    static YagaPublicationEvidence parse(int status, String body, long requestedId, String expectedShop) {
        try {
            if (body == null || body.length() > 1_000_000) {
                return unreadable(status, "RESPONSE_BODY_UNAVAILABLE");
            }
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            String caseId = safeCaseId(data.path("caseId").asText(""));
            String code = safeCode(data.path("code").asText(data.path("errorCode").asText("")));
            if (status >= 400 || "error".equals(root.path("status").asText()) ||
                    "fail".equals(root.path("status").asText())) {
                return new YagaPublicationEvidence(CONFIRMED_FAILURE, status, caseId, code,
                        "API_REJECTED_REQUEST", null, null, false);
            }
            long id = data.path("id").asLong(0);
            String slug = data.path("slug").asText("");
            String shop = data.path("shop").path("slug").asText("");
            if (status >= 200 && status < 300 && "success".equals(root.path("status").asText()) &&
                    "published".equals(data.path("status").asText()) && id == requestedId && id > 0 &&
                    slug.matches("[a-z0-9][a-z0-9_-]{1,149}") &&
                    (shop.isEmpty() || expectedShop.equals(shop)) &&
                    (data.path("hiddenAt").isMissingNode() || data.path("hiddenAt").isNull()) &&
                    (data.path("deletedAt").isMissingNode() || data.path("deletedAt").isNull())) {
                return new YagaPublicationEvidence(CONFIRMED_SUCCESS, status, null, null,
                        "API_PUBLISHED_IDENTITY", id, slug, true);
            }
            return unknown(status, "UNRECOGNIZED_OR_CONFLICTING_RESPONSE");
        } catch (RuntimeException ignored) {
            return unreadable(status, "NON_JSON_OR_INVALID_RESPONSE");
        }
    }

    static YagaPublicationEvidence unknown(Integer status, String reason) {
        return new YagaPublicationEvidence(RESULT_UNKNOWN, status, null, null, reason, null, null, false);
    }

    static boolean allowsDraftValidation(int status, String body, long id, String slug, String shop) {
        try {
            if (status < 200 || status >= 300 || body == null || body.length() > 1_000_000) return false;
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            // PATCH may return an unfamiliar status. Only the subsequent detail check proves publication.
            return root.isObject() &&
                    (absent(root.path("status")) || "success".equals(root.path("status").asText())) &&
                    (absent(data) || data.isObject()) && !hasErrorSignal(root) &&
                    (absent(data.path("id")) || data.path("id").asLong(0) == id) &&
                    (absent(data.path("slug")) || data.path("slug").asText().equals(slug)) &&
                    !List.of("draft", "hidden", "deleted").contains(data.path("status").asText("")) &&
                    (absent(data.path("shop").path("slug")) || shop.equals(data.path("shop").path("slug").asText())) &&
                    absent(data.path("hiddenAt")) && absent(data.path("deletedAt"));
        } catch (RuntimeException ignored) { return false; }
    }

    static boolean matchesPreparedIdentity(String body, Long id, String slug) {
        try {
            if (body == null || body.length() > 1_000_000 || id == null || id <= 0 || slug == null) return false;
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            return "success".equals(root.path("status").asText()) &&
                    data.path("id").isIntegralNumber() && data.path("id").asLong(0) == id &&
                    data.path("slug").isTextual() && slug.equals(data.path("slug").asText());
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean hasErrorSignal(JsonNode node) {
        if (node.isObject()) {
            for (String field : List.of("error", "errors", "caseId", "code", "errorCode")) {
                if (!absent(node.path(field))) return true;
            }
            for (String field : List.of("status", "result")) {
                if (List.of("error", "fail", "failed", "failure").contains(node.path(field).asText(""))) return true;
            }
            if (node.path("success").isBoolean() && !node.path("success").asBoolean()) return true;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) if (hasErrorSignal(child)) return true;
        }
        return false;
    }

    static String checks(String body, long requestedId, String shop) {
        try {
            if (body == null || body.length() > 1_000_000) return "UNAVAILABLE";
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            long id = data.path("id").asLong(0);
            String slug = data.path("slug").asText("");
            String actualShop = data.path("shop").path("slug").asText("");
            return "rootStatus=" + safeStatus(root.path("status")) +
                    " dataStatus=" + safeStatus(data.path("status")) +
                    " requestedId=" + requestedId + " responseId=" + id +
                    " responseSlug=" + (slug.matches("[a-z0-9][a-z0-9_-]{1,149}") ? slug : "MISSING_OR_INVALID") +
                    " rootSuccess=" + "success".equals(root.path("status").asText()) +
                    " published=" + "published".equals(data.path("status").asText()) +
                    " idMatch=" + (id > 0 && id == requestedId) +
                    " slugValid=" + slug.matches("[a-z0-9][a-z0-9_-]{1,149}") +
                    " shopMatch=" + (actualShop.isEmpty() || shop.equals(actualShop)) +
                    " notHidden=" + absent(data.path("hiddenAt")) +
                    " notDeleted=" + absent(data.path("deletedAt"));
        } catch (RuntimeException ignored) { return "NON_JSON"; }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static String safeStatus(JsonNode node) {
        if (absent(node)) return node.getNodeType().toString();
        String value = node.asText("");
        return List.of("success", "published", "error", "fail", "draft", "hidden", "deleted").contains(value)
                ? value : "UNRECOGNIZED";
    }

    private static YagaPublicationEvidence unreadable(int status, String reason) {
        return new YagaPublicationEvidence(status >= 400 ? CONFIRMED_FAILURE : RESULT_UNKNOWN,
                status, null, null, reason, null, null, false);
    }

    static String shape(String body) {
        try {
            if (body == null || body.length() > 1_000_000) return "UNAVAILABLE";
            JsonNode root = JSON.readTree(body);
            return List.of("status", "data", "data.id", "data.slug", "data.status", "data.caseId",
                            "data.code", "data.errorCode", "data.message", "data.msg").stream()
                    .map(path -> path + "=" + root.at("/" + path.replace('.', '/')).getNodeType())
                    .collect(Collectors.joining(","));
        } catch (RuntimeException ignored) { return "NON_JSON"; }
    }

    static boolean imageOrderSaved(int status, String body) {
        try {
            if (status < 200 || status >= 300 || body == null || body.length() > 1_000_000) return false;
            JsonNode root = JSON.readTree(body);
            return "success".equals(root.path("status").asText()) && root.path("data").isArray();
        } catch (RuntimeException ignored) { return false; }
    }

    static String safeCaseId(String value) {
        return value != null && value.matches("EE-A-[A-Za-z0-9-]{1,100}") ? value : null;
    }

    static String safeCode(String value) {
        // Only known machine codes, never arbitrary server messages or echoed input.
        return "NO_ENABLED_COURIER_OPTIONS".equals(value) ? value : null;
    }
}
