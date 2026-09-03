package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.exception.YagaPublishingFormException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class YagaPublishedUrlResolver {

    private static final String CREATE_FORM_PATH = "/muuk/lisa-toode";
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{1,149}");

    public YagaPublishedUrl resolve(
            String url,
            String fallbackShopSlug
    ) {
        URI uri = parseHttpsYagaUri(url);
        String[] segments = uri.getPath().split("/");

        if (isPublicProductPath(segments)) {
            String shopSlug = segments[1];
            String productSlug = segments[3];
            return new YagaPublishedUrl(
                    url,
                    publicUrl(shopSlug, productSlug),
                    shopSlug,
                    productSlug,
                    true
            );
        }

        if (isIntermediatePublishPath(segments)) {
            String productSlug = segments[3];
            if (!isValidSlug(fallbackShopSlug)) {
                throw new YagaPublishingFormException(
                        "Yaga shop slug is required to resolve published URL"
                );
            }

            return new YagaPublishedUrl(
                    url,
                    publicUrl(fallbackShopSlug, productSlug),
                    fallbackShopSlug,
                    productSlug,
                    false
            );
        }

        throw new YagaPublishingFormException(
                "Unsupported Yaga post-publish URL"
        );
    }

    public boolean isResolvable(
            String url,
            String fallbackShopSlug
    ) {
        try {
            resolve(url, fallbackShopSlug);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private URI parseHttpsYagaUri(String url) {
        if (url == null || url.isBlank()) {
            throw new YagaPublishingFormException(
                    "Yaga published URL is required"
            );
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new YagaPublishingFormException(
                    "Invalid Yaga published URL",
                    exception
            );
        }

        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                (!"www.yaga.ee".equalsIgnoreCase(host) &&
                        !"yaga.ee".equalsIgnoreCase(host))) {
            throw new YagaPublishingFormException(
                    "Yaga published URL must point to yaga.ee"
            );
        }

        return uri;
    }

    private boolean isPublicProductPath(String[] segments) {
        return segments.length == 4 &&
                isValidSlug(segments[1]) &&
                "toode".equals(segments[2]) &&
                isValidSlug(segments[3]);
    }

    private boolean isIntermediatePublishPath(String[] segments) {
        return segments.length == 4 &&
                "muuk".equals(segments[1]) &&
                "lisa-toode".equals(segments[2]) &&
                isValidSlug(segments[3]);
    }

    private boolean isValidSlug(String value) {
        return value != null &&
                SLUG_PATTERN.matcher(value).matches();
    }

    private String publicUrl(
            String shopSlug,
            String productSlug
    ) {
        return "https://www.yaga.ee/" +
                shopSlug +
                "/toode/" +
                productSlug;
    }
}
