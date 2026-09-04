package ee.nikolas.resalepilot.service;

import java.net.URI;
import java.net.URISyntaxException;

final class YagaPublicProductUrlValidator {

    private YagaPublicProductUrlValidator() {
    }

    static boolean isExpectedPublicProductUrl(
            String url,
            String expectedShopSlug,
            String expectedProductSlug
    ) {
        if (isBlank(url) ||
                isBlank(expectedShopSlug) ||
                isBlank(expectedProductSlug)) {
            return false;
        }

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String expectedPath =
                    "/" +
                            expectedShopSlug +
                            "/toode/" +
                            expectedProductSlug;

            return "https".equals(uri.getScheme()) &&
                    ("www.yaga.ee".equals(host) ||
                            "yaga.ee".equals(host)) &&
                    expectedPath.equals(uri.getPath()) &&
                    uri.getQuery() == null &&
                    uri.getFragment() == null;

        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
