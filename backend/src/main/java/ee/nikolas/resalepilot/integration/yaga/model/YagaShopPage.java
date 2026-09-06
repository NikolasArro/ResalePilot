package ee.nikolas.resalepilot.integration.yaga.model;

import java.util.List;

public record YagaShopPage(
        String pageUrl,
        List<ProductLink> productLinks,
        String nextPageUrl,
        boolean sourceIdentified,
        boolean confirmedEmpty,
        YagaShopPageDiagnostics diagnostics
) {

    public YagaShopPage(
            String pageUrl,
            List<ProductLink> productLinks,
            String nextPageUrl
    ) {
        this(pageUrl, productLinks, nextPageUrl, true, false, null);
    }

    public record ProductLink(
            String shopSlug,
            String productSlug,
            String publicUrl
    ) {
    }

    public record YagaShopPageDiagnostics(
            String requestedUrl,
            String finalUrl,
            int httpStatus,
            String contentType,
            int responseBodyLength,
            String pageTitle,
            boolean nextDataPresent,
            int nextDataLength,
            int anchorCount,
            int productHrefCount,
            List<String> productHrefExamples,
            int scriptTagCount,
            List<String> scriptSrcExamples,
            boolean loginOrSignInDetected,
            boolean challengeOrCaptchaDetected,
            boolean accessDeniedDetected,
            boolean shopSlugPresent
    ) {
    }
}
