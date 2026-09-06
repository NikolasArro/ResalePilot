package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.client.YagaShopPageClient;
import ee.nikolas.resalepilot.integration.yaga.client.YagaShopPageClient.YagaShopListingSourceException;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage;
import ee.nikolas.resalepilot.integration.yaga.model.YagaShopPage.YagaShopPageDiagnostics;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.config.YagaShopDiscoveryProperties;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryFailedResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoverySkippedResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryException;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaProductTitleResolver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class YagaShopDiscoveryService {

    private final YagaShopDiscoveryProperties properties;
    private final YagaShopPageClient shopPageClient;
    private final YagaPageDataClient pageDataClient;
    private final MarketplaceListingRepository listingRepository;
    private final YagaProductTitleResolver titleResolver;
    private final YagaShopUrlBuilder urlBuilder =
            new YagaShopUrlBuilder();

    public YagaShopDiscoveryService(
            YagaShopDiscoveryProperties properties,
            YagaShopPageClient shopPageClient,
            YagaPageDataClient pageDataClient,
            MarketplaceListingRepository listingRepository,
            YagaProductTitleResolver titleResolver
    ) {
        this.properties = properties;
        this.shopPageClient = shopPageClient;
        this.pageDataClient = pageDataClient;
        this.listingRepository = listingRepository;
        this.titleResolver = titleResolver;
    }

    public YagaShopDiscoveryResponse discover(String shopSlug) {
        urlBuilder.validateShopSlug(shopSlug);

        DiscoveryAccumulator accumulator =
                new DiscoveryAccumulator(shopSlug);
        String nextPageUrl = null;
        int pageNumber = 1;
        Set<String> visitedPageUrls = new HashSet<>();

        while (pageNumber <= properties.maxPages() &&
                accumulator.uniqueCandidates.size() <
                        properties.maxListings()) {
            YagaShopPage page = loadShopPage(shopSlug, pageNumber);
            accumulator.pagesVisited++;
            if (!visitedPageUrls.add(page.pageUrl())) {
                accumulator.truncated = true;
                break;
            }

            for (YagaShopPage.ProductLink link : page.productLinks()) {
                accumulator.cardsDiscovered++;
                if (accumulator.uniqueCandidates.size() >=
                        properties.maxListings()) {
                    accumulator.truncated = true;
                    break;
                }
                discoverCandidate(accumulator, link);
            }

            if (accumulator.truncated || page.nextPageUrl() == null) {
                break;
            }
            if (page.nextPageUrl().equals(nextPageUrl) ||
                    visitedPageUrls.contains(page.nextPageUrl())) {
                accumulator.truncated = true;
                break;
            }

            nextPageUrl = page.nextPageUrl();
            pageNumber++;
            delayBetweenRequests();
        }

        if (pageNumber > properties.maxPages()) {
            accumulator.truncated = true;
        }

        return accumulator.toResponse();
    }

    private YagaShopPage loadShopPage(
            String shopSlug,
            int pageNumber
    ) {
        try {
            return shopPageClient.getPage(
                    shopSlug,
                    pageNumber,
                    properties.requestTimeout()
            );
        } catch (YagaShopListingSourceException exception) {
            throw new YagaShopDiscoveryException(
                    exception.getMessage(),
                    exception,
                    details(exception.diagnostics())
            );
        } catch (RuntimeException exception) {
            throw new YagaShopDiscoveryException(
                    "Failed to load Yaga shop listings",
                    exception
            );
        }
    }

    private void discoverCandidate(
            DiscoveryAccumulator accumulator,
            YagaShopPage.ProductLink link
    ) {
        String key = link.shopSlug() + "/" + link.productSlug();
        if (accumulator.uniqueCandidates.containsKey(key)) {
            accumulator.skipped.add(
                    new YagaShopDiscoverySkippedResponse(
                            link.productSlug(),
                            YagaShopDiscoveryReason.DUPLICATE
                    )
            );
            return;
        }
        accumulator.uniqueCandidates.put(key, link);

        if (!accumulator.shopSlug.equals(link.shopSlug())) {
            accumulator.skipped.add(
                    new YagaShopDiscoverySkippedResponse(
                            link.productSlug(),
                            YagaShopDiscoveryReason.WRONG_SHOP
                    )
            );
            return;
        }

        YagaImportedProductData data;
        try {
            delayBetweenRequests();
            data = pageDataClient.getProduct(link.publicUrl());
        } catch (RuntimeException exception) {
            accumulator.failed.add(
                    new YagaShopDiscoveryFailedResponse(
                            link.productSlug(),
                            YagaShopDiscoveryReason.FETCH_FAILED,
                            safeMessage(exception)
                    )
            );
            return;
        }

        Classification classification = classify(
                accumulator.shopSlug,
                link.productSlug(),
                data
        );

        if (classification.skippedReason() != null) {
            accumulator.skipped.add(
                    new YagaShopDiscoverySkippedResponse(
                            link.productSlug(),
                            classification.skippedReason()
                    )
            );
            return;
        }
        if (classification.failedReason() != null) {
            accumulator.failed.add(
                    new YagaShopDiscoveryFailedResponse(
                            link.productSlug(),
                            classification.failedReason(),
                            classification.safeMessage()
                    )
            );
            return;
        }

        YagaShopDiscoveredListingResponse discovered =
                new YagaShopDiscoveredListingResponse(
                        data.externalId().toString(),
                        data.productSlug(),
                        titleResolver.resolve(data).orElse(null),
                        urlBuilder.publicProductUrl(
                                data.shopSlug(),
                                data.productSlug()
                        ),
                        data.createdAt(),
                        data.images().size()
                );

        boolean existingByExternalId =
                listingRepository.existsByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        data.externalId().toString()
                );
        boolean existingBySlug =
                listingRepository
                        .findByMarketplaceAndShopSlugAndProductSlug(
                                Marketplace.YAGA,
                                data.shopSlug(),
                                data.productSlug()
                        )
                        .isPresent();

        if (existingByExternalId || existingBySlug) {
            accumulator.activeExisting.add(discovered);
        } else {
            accumulator.activeNew.add(discovered);
        }
    }

    private Classification classify(
            String expectedShopSlug,
            String expectedProductSlug,
            YagaImportedProductData data
    ) {
        if (data == null ||
                data.externalId() == null ||
                data.productSlug() == null ||
                data.shopSlug() == null) {
            return Classification.failed(
                    YagaShopDiscoveryReason.INVALID_DATA,
                    "Yaga product data is incomplete"
            );
        }
        if (!expectedShopSlug.equals(data.shopSlug())) {
            return Classification.skipped(
                    YagaShopDiscoveryReason.WRONG_SHOP
            );
        }
        if (!expectedProductSlug.equals(data.productSlug())) {
            return Classification.failed(
                    YagaShopDiscoveryReason.INVALID_DATA,
                    "Yaga product slug mismatch"
            );
        }
        if (data.deletedAt() != null) {
            return Classification.skipped(
                    YagaShopDiscoveryReason.DELETED
            );
        }
        if (data.hiddenAt() != null) {
            return Classification.skipped(
                    YagaShopDiscoveryReason.HIDDEN
            );
        }

        String status = data.status() == null
                ? ""
                : data.status().trim().toLowerCase();
        return switch (status) {
            case "published" -> Classification.active();
            case "not-visible" -> Classification.skipped(
                    YagaShopDiscoveryReason.NOT_VISIBLE
            );
            case "hidden" -> Classification.skipped(
                    YagaShopDiscoveryReason.HIDDEN
            );
            case "sold" -> Classification.skipped(
                    YagaShopDiscoveryReason.SOLD
            );
            case "deleted" -> Classification.skipped(
                    YagaShopDiscoveryReason.DELETED
            );
            default -> Classification.failed(
                    YagaShopDiscoveryReason.UNKNOWN_STATUS,
                    "Unknown Yaga listing status"
            );
        };
    }

    private void delayBetweenRequests() {
        Duration delay = properties.requestDelay();
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new YagaShopDiscoveryException(
                    "Yaga shop discovery was interrupted",
                    exception
            );
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private Map<String, String> details(
            YagaShopPageDiagnostics diagnostics
    ) {
        if (diagnostics == null) {
            return Map.of();
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("requestedUrl", value(diagnostics.requestedUrl()));
        details.put("finalUrl", value(diagnostics.finalUrl()));
        details.put("httpStatus", String.valueOf(diagnostics.httpStatus()));
        details.put("contentType", value(diagnostics.contentType()));
        details.put(
                "responseBodyLength",
                String.valueOf(diagnostics.responseBodyLength())
        );
        details.put("pageTitle", value(diagnostics.pageTitle()));
        details.put(
                "nextDataPresent",
                String.valueOf(diagnostics.nextDataPresent())
        );
        details.put(
                "nextDataLength",
                String.valueOf(diagnostics.nextDataLength())
        );
        details.put("anchorCount", String.valueOf(diagnostics.anchorCount()));
        details.put(
                "productHrefCount",
                String.valueOf(diagnostics.productHrefCount())
        );
        details.put(
                "productHrefExamples",
                diagnostics.productHrefExamples().toString()
        );
        details.put(
                "scriptTagCount",
                String.valueOf(diagnostics.scriptTagCount())
        );
        details.put(
                "scriptSrcExamples",
                diagnostics.scriptSrcExamples().toString()
        );
        details.put(
                "loginOrSignInDetected",
                String.valueOf(diagnostics.loginOrSignInDetected())
        );
        details.put(
                "challengeOrCaptchaDetected",
                String.valueOf(diagnostics.challengeOrCaptchaDetected())
        );
        details.put(
                "accessDeniedDetected",
                String.valueOf(diagnostics.accessDeniedDetected())
        );
        details.put(
                "shopSlugPresent",
                String.valueOf(diagnostics.shopSlugPresent())
        );
        return details;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private static class DiscoveryAccumulator {
        private final String shopSlug;
        private final Map<String, YagaShopPage.ProductLink> uniqueCandidates =
                new LinkedHashMap<>();
        private final List<YagaShopDiscoveredListingResponse> activeNew =
                new ArrayList<>();
        private final List<YagaShopDiscoveredListingResponse> activeExisting =
                new ArrayList<>();
        private final List<YagaShopDiscoverySkippedResponse> skipped =
                new ArrayList<>();
        private final List<YagaShopDiscoveryFailedResponse> failed =
                new ArrayList<>();
        private int pagesVisited;
        private int cardsDiscovered;
        private boolean truncated;

        private DiscoveryAccumulator(String shopSlug) {
            this.shopSlug = shopSlug;
        }

        private YagaShopDiscoveryResponse toResponse() {
            int activePublishedCount =
                    activeNew.size() + activeExisting.size();
            return new YagaShopDiscoveryResponse(
                    shopSlug,
                    pagesVisited,
                    cardsDiscovered,
                    uniqueCandidates.size(),
                    activePublishedCount,
                    activeNew.size(),
                    activeExisting.size(),
                    skipped.size(),
                    failed.size(),
                    truncated,
                    List.copyOf(activeNew),
                    List.copyOf(activeExisting),
                    List.copyOf(skipped),
                    List.copyOf(failed)
            );
        }
    }

    private record Classification(
            YagaShopDiscoveryReason skippedReason,
            YagaShopDiscoveryReason failedReason,
            String safeMessage
    ) {
        private static Classification active() {
            return new Classification(null, null, null);
        }

        private static Classification skipped(
                YagaShopDiscoveryReason reason
        ) {
            return new Classification(reason, null, null);
        }

        private static Classification failed(
                YagaShopDiscoveryReason reason,
                String safeMessage
        ) {
            return new Classification(null, reason, safeMessage);
        }
    }
}
