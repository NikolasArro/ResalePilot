package ee.nikolas.resalepilot.integration.yaga.model;

import java.time.Instant;
import java.util.List;

public record YagaShopPage(
        String pageUrl,
        List<ProductLink> productLinks,
        String nextPageUrl,
        boolean completenessConfirmed,
        boolean sourceIdentified,
        boolean confirmedEmpty,
        Long trustedShopId,
        YagaShopPageDiagnostics diagnostics
) {

    public YagaShopPage(
            String pageUrl,
            List<ProductLink> productLinks,
            String nextPageUrl
    ) {
        this(
                pageUrl,
                productLinks,
                nextPageUrl,
                false,
                true,
                false,
                null,
                null
        );
    }

    public YagaShopPage(
            String pageUrl,
            List<ProductLink> productLinks,
            String nextPageUrl,
            boolean completenessConfirmed,
            boolean sourceIdentified,
            boolean confirmedEmpty,
            YagaShopPageDiagnostics diagnostics
    ) {
        this(
                pageUrl,
                productLinks,
                nextPageUrl,
                completenessConfirmed,
                sourceIdentified,
                confirmedEmpty,
                null,
                diagnostics
        );
    }

    public record ProductLink(
            String shopSlug,
            String productSlug,
            String publicUrl,
            Long externalListingId,
            Instant externalCreatedAt,
            int imageCount
    ) {
        public ProductLink(
                String shopSlug,
                String productSlug,
                String publicUrl
        ) {
            this(shopSlug, productSlug, publicUrl, null, null, 0);
        }
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
            boolean shopSlugPresent,
            int initialItemCount,
            Integer declaredTotal,
            String declaredTotalSource,
            boolean declaredTotalTrusted,
            List<String> rejectedTotalCandidates,
            List<String> paginationFields,
            Boolean hasNextPage,
            String hasNextSource,
            boolean nextCursorAvailable,
            String nextRequestPath,
            String continuationSource,
            String candidateArraySource,
            List<String> relevantRouteNames,
            boolean trustedShopIdFound,
            String trustedShopIdSource,
            boolean completenessConfirmed
    ) {
        public YagaShopPageDiagnostics(
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
                boolean shopSlugPresent,
                int initialItemCount,
                Integer declaredTotal,
                String declaredTotalSource,
                boolean declaredTotalTrusted,
                List<String> rejectedTotalCandidates,
                List<String> paginationFields,
                Boolean hasNextPage,
                String hasNextSource,
                boolean nextCursorAvailable,
                String nextRequestPath,
                String continuationSource,
                String candidateArraySource,
                List<String> relevantRouteNames,
                boolean completenessConfirmed
        ) {
            this(
                    requestedUrl, finalUrl, httpStatus, contentType,
                    responseBodyLength, pageTitle, nextDataPresent,
                    nextDataLength, anchorCount, productHrefCount,
                    productHrefExamples, scriptTagCount, scriptSrcExamples,
                    loginOrSignInDetected, challengeOrCaptchaDetected,
                    accessDeniedDetected, shopSlugPresent, initialItemCount,
                    declaredTotal, declaredTotalSource, declaredTotalTrusted,
                    rejectedTotalCandidates, paginationFields, hasNextPage,
                    hasNextSource, nextCursorAvailable, nextRequestPath,
                    continuationSource, candidateArraySource,
                    relevantRouteNames, false, null, completenessConfirmed
            );
        }
    }
}
