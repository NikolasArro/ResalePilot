package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class YagaShopIdentityVerifier {

    private static final Pattern SAFE_SHOP_SLUG =
            Pattern.compile("[a-z0-9][a-z0-9-]{1,149}");

    private static final Set<String> RESERVED_ROOT_PATHS = Set.of(
            "_next",
            "api",
            "auth",
            "blog",
            "cart",
            "checkout",
            "en",
            "et",
            "help",
            "konto",
            "login",
            "logi-sisse",
            "logout",
            "muuk",
            "ostukorv",
            "pood",
            "profile",
            "profiil",
            "registreeri",
            "ru",
            "search",
            "seaded",
            "settings",
            "toode"
    );

    private YagaShopIdentityVerifier() {
    }

    public static ShopIdentityResult verify(
            String expectedShopSlug,
            Collection<String> visibleUrls
    ) {
        return verifyUrls(expectedShopSlug, visibleUrls, false);
    }

    public static ShopIdentityResult verifySelectedPage(
            String expectedShopSlug,
            String selectedPageUrl,
            Collection<String> supportingUrls
    ) {
        ShopIdentityResult selectedPageResult = verifyUrls(
                expectedShopSlug,
                List.of(selectedPageUrl),
                true
        );
        if (selectedPageResult.accepted() ||
                !"NO_SHOP_URL_SIGNAL_FOUND".equals(
                        selectedPageResult.failureReason()
                )) {
            return selectedPageResult;
        }

        return ShopIdentityResult.rejected(
                "Authenticated Yaga shop could not be verified",
                firstDetectedSlug(supportingUrls),
                "SELECTED_PAGE_URL",
                "SELECTED_PAGE_HAS_NO_SHOP_URL_SIGNAL"
        );
    }

    public static ShopIdentityResult verifyAuthenticatedShop(
            String expectedShopSlug,
            String authenticatedShopSlug,
            String verificationSource,
            String failureReason
    ) {
        if (expectedShopSlug == null || expectedShopSlug.isBlank()) {
            return ShopIdentityResult.rejected(
                    "Expected shop slug is missing",
                    normalizedSlug(authenticatedShopSlug),
                    verificationSource,
                    "EXPECTED_SHOP_SLUG_MISSING"
            );
        }
        String expected = expectedShopSlug.toLowerCase(Locale.ROOT);
        String detected = normalizedSlug(authenticatedShopSlug);
        if (detected == null) {
            return ShopIdentityResult.rejected(
                    "Authenticated Yaga shop could not be verified",
                    null,
                    verificationSource,
                    failureReason == null || failureReason.isBlank()
                            ? "NO_AUTHENTICATED_SHOP_SIGNAL_FOUND"
                            : failureReason
            );
        }
        if (detected.equals(expected)) {
            return ShopIdentityResult.accepted(
                    expected,
                    detected,
                    verificationSource == null || verificationSource.isBlank()
                            ? "AUTHENTICATED_SHOP_SIGNAL"
                            : verificationSource
            );
        }
        return ShopIdentityResult.rejected(
                "Authenticated Yaga shop does not match selected account",
                detected,
                verificationSource,
                "DETECTED_AUTHENTICATED_SHOP_DOES_NOT_MATCH_EXPECTED"
        );
    }

    private static ShopIdentityResult verifyUrls(
            String expectedShopSlug,
            Collection<String> visibleUrls,
            boolean selectedPageOnly
    ) {
        if (expectedShopSlug == null || expectedShopSlug.isBlank()) {
            return ShopIdentityResult.rejected(
                    "Expected shop slug is missing",
                    null,
                    null,
                    "EXPECTED_SHOP_SLUG_MISSING"
            );
        }

        String expected = expectedShopSlug.toLowerCase(Locale.ROOT);
        List<DetectedShop> detected = new ArrayList<>();
        for (String url : visibleUrls) {
            DetectedShop shop = shopSlug(url);
            if (shop != null) {
                detected.add(shop);
            }
        }

        for (DetectedShop shop : detected) {
            if (shop.source() == VerificationSource.SHOP_PROFILE_URL &&
                    expected.equals(shop.slug())) {
                return ShopIdentityResult.accepted(
                        expected,
                        shop.slug(),
                        shop.source().name()
                );
            }
        }

        Set<String> slugs = new LinkedHashSet<>();
        for (DetectedShop shop : detected) {
            slugs.add(shop.slug());
        }
        if (slugs.contains(expected)) {
            if (slugs.size() == 1) {
                return ShopIdentityResult.accepted(
                        expected,
                        expected,
                        detected.getFirst().source().name()
                );
            }
            return ShopIdentityResult.rejected(
                    "Authenticated Yaga shop could not be verified",
                    firstSlug(slugs),
                    selectedPageOnly
                            ? "SELECTED_PAGE_URL"
                            : "AMBIGUOUS_SHOP_URLS",
                    "MULTIPLE_SHOP_SLUGS_DETECTED"
            );
        }
        if (!slugs.isEmpty()) {
            return ShopIdentityResult.rejected(
                    "Authenticated Yaga shop does not match selected account",
                    firstSlug(slugs),
                    detected.getFirst().source().name(),
                    "DETECTED_SHOP_DOES_NOT_MATCH_EXPECTED"
            );
        }
        return ShopIdentityResult.rejected(
                "Authenticated Yaga shop could not be verified",
                null,
                null,
                "NO_SHOP_URL_SIGNAL_FOUND"
        );
    }

    private static String firstDetectedSlug(Collection<String> urls) {
        if (urls == null) {
            return null;
        }
        for (String url : urls) {
            DetectedShop shop = shopSlug(url);
            if (shop != null) {
                return shop.slug();
            }
        }
        return null;
    }

    private static DetectedShop shopSlug(String url) {
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
            List<String> parts = pathSegments(path);
            for (int index = 0; index < parts.size() - 1; index++) {
                if ("pood".equals(parts.get(index))) {
                    String candidate = normalizedSlug(parts.get(index + 1));
                    return candidate == null
                            ? null
                            : new DetectedShop(
                                    candidate,
                                    VerificationSource.SHOP_PROFILE_URL
                            );
                }
            }
            if (parts.size() == 1) {
                String candidate = normalizedSlug(parts.getFirst());
                if (candidate != null &&
                        !RESERVED_ROOT_PATHS.contains(candidate)) {
                    return new DetectedShop(
                            candidate,
                            VerificationSource.SHOP_ROOT_URL
                    );
                }
            }
            return null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static List<String> pathSegments(String path) {
        String[] split = path.split("/");
        List<String> segments = new ArrayList<>();
        for (String segment : split) {
            if (!segment.isBlank()) {
                segments.add(segment.toLowerCase(Locale.ROOT));
            }
        }
        return segments;
    }

    private static String normalizedSlug(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.toLowerCase(Locale.ROOT);
        return SAFE_SHOP_SLUG.matcher(candidate).matches()
                ? candidate
                : null;
    }

    private static String firstSlug(Set<String> slugs) {
        return slugs.iterator().next();
    }

    private enum VerificationSource {
        SHOP_PROFILE_URL,
        SHOP_ROOT_URL
    }

    private record DetectedShop(
            String slug,
            VerificationSource source
    ) {
    }

    public record ShopIdentityResult(
            boolean accepted,
            String verifiedShopSlug,
            String safeMessage,
            String detectedShopSlug,
            String verificationSource,
            String failureReason
    ) {
        static ShopIdentityResult accepted(
                String verifiedShopSlug,
                String detectedShopSlug,
                String verificationSource
        ) {
            return new ShopIdentityResult(
                    true,
                    verifiedShopSlug,
                    null,
                    detectedShopSlug,
                    verificationSource,
                    null
            );
        }

        static ShopIdentityResult rejected(
                String safeMessage,
                String detectedShopSlug,
                String verificationSource,
                String failureReason
        ) {
            return new ShopIdentityResult(
                    false,
                    null,
                    safeMessage,
                    detectedShopSlug,
                    verificationSource,
                    failureReason
            );
        }
    }
}
