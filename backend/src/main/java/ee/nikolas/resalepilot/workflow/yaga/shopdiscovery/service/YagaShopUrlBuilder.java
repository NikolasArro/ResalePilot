package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service;

import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryRequestInvalidException;

import java.net.URI;
import java.net.URISyntaxException;

public class YagaShopUrlBuilder {

    private static final String SAFE_SLUG_PATTERN = "[a-zA-Z0-9_-]+";

    public String publicProductUrl(
            String shopSlug,
            String productSlug
    ) {
        validateShopSlug(shopSlug);
        validateProductSlug(productSlug);
        return "https://www.yaga.ee/" +
                shopSlug +
                "/toode/" +
                productSlug;
    }

    public void validateShopSlug(String shopSlug) {
        if (shopSlug == null ||
                !shopSlug.matches(SAFE_SLUG_PATTERN) ||
                shopSlug.contains("..")) {
            throw new YagaShopDiscoveryRequestInvalidException(
                    "Invalid Yaga shop slug"
            );
        }
    }

    public void validateProductSlug(String productSlug) {
        if (productSlug == null ||
                !productSlug.matches(SAFE_SLUG_PATTERN) ||
                productSlug.contains("..")) {
            throw new YagaShopDiscoveryRequestInvalidException(
                    "Invalid Yaga product slug"
            );
        }
    }

    public boolean isExpectedPublicProductUrl(
            String url,
            String expectedShopSlug,
            String expectedProductSlug
    ) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String expectedPath =
                    "/" +
                            expectedShopSlug +
                            "/toode/" +
                            expectedProductSlug;

            return "https".equalsIgnoreCase(uri.getScheme()) &&
                    ("www.yaga.ee".equalsIgnoreCase(host) ||
                            "yaga.ee".equalsIgnoreCase(host)) &&
                    expectedPath.equals(uri.getPath()) &&
                    uri.getQuery() == null &&
                    uri.getFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
