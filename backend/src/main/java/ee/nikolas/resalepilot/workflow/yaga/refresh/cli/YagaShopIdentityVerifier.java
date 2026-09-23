package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class YagaShopIdentityVerifier {

    private YagaShopIdentityVerifier() {
    }

    public static ShopIdentityResult verify(
            String expectedShopSlug,
            Collection<String> visibleUrls
    ) {
        if (expectedShopSlug == null || expectedShopSlug.isBlank()) {
            return ShopIdentityResult.rejected("Expected shop slug is missing");
        }

        Set<String> slugs = new LinkedHashSet<>();
        for (String url : visibleUrls) {
            String slug = shopSlug(url);
            if (slug != null) {
                slugs.add(slug);
            }
        }

        if (slugs.contains(expectedShopSlug)) {
            return ShopIdentityResult.accepted(expectedShopSlug);
        }
        if (!slugs.isEmpty()) {
            return ShopIdentityResult.rejected(
                    "Authenticated Yaga shop does not match selected account"
            );
        }
        return ShopIdentityResult.rejected(
                "Authenticated Yaga shop could not be verified"
        );
    }

    private static String shopSlug(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"yaga.ee".equals(host) && !"www.yaga.ee".equals(host)) {
                return null;
            }
            String path = uri.getPath();
            if (path == null) {
                return null;
            }
            String[] parts = path.split("/");
            for (int index = 0; index < parts.length - 1; index++) {
                if ("pood".equals(parts[index])) {
                    String candidate = parts[index + 1];
                    return candidate.isBlank()
                            ? null
                            : candidate.toLowerCase(Locale.ROOT);
                }
            }
            return null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    public record ShopIdentityResult(
            boolean accepted,
            String verifiedShopSlug,
            String safeMessage
    ) {
        static ShopIdentityResult accepted(String shopSlug) {
            return new ShopIdentityResult(true, shopSlug, null);
        }

        static ShopIdentityResult rejected(String safeMessage) {
            return new ShopIdentityResult(false, null, safeMessage);
        }
    }
}
