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
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryStopReason;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception.YagaShopDiscoveryException;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaProductTitleResolver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        YagaShopPage initialPage = loadInitialShopPage(shopSlug);
        accumulator.captureDiagnostics(initialPage, true);
        if (initialPage.trustedShopId() == null) {
            accumulator.stop(
                    YagaShopDiscoveryStopReason.PAGINATION_NOT_DISCOVERED,
                    false
            );
        }

        int limit = properties.apiPageSize();
        int offset = 0;
        Set<String> pageFingerprints = new HashSet<>();
        while (accumulator.stopReason == null) {
            if (accumulator.pagesVisited >= properties.maxPages()) {
                accumulator.stop(YagaShopDiscoveryStopReason.MAX_PAGES, false);
                break;
            }
            if (accumulator.uniqueCandidates.size() >=
                    properties.maxListings()) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.MAX_LISTINGS,
                        false
                );
                break;
            }

            delayBetweenRequests();
            accumulator.offsetsRequested.add(offset);
            YagaShopPage apiPage = loadPublishedProductsPage(
                    shopSlug,
                    initialPage.trustedShopId(),
                    offset,
                    limit
            );
            accumulator.pagesVisited++;
            if (!apiPage.sourceIdentified()) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        false
                );
                break;
            }
            accumulator.captureDiagnostics(apiPage, false);
            int rawBatchSize = apiPage.diagnostics().initialItemCount();
            if (!accumulator.observeRawBatch(offset, rawBatchSize)) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        false
                );
                break;
            }
            String fingerprint = pageFingerprint(apiPage, rawBatchSize);
            if (rawBatchSize == limit && !pageFingerprints.add(fingerprint)) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        false
                );
                break;
            }
            discoverPage(accumulator, apiPage);
            if (accumulator.stopReason != null) {
                break;
            }
            if (rawBatchSize < limit) {
                boolean complete = accumulator.maximumObservedTotal != null &&
                        accumulator.cumulativeRawCount >=
                                accumulator.maximumObservedTotal &&
                        accumulator.uniqueCandidates.size() >=
                                accumulator.maximumObservedTotal &&
                        !accumulator.conflictingDuplicateIdentity;
                accumulator.stop(
                        complete
                                ? YagaShopDiscoveryStopReason.CONFIRMED_END
                                : YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        complete
                );
                break;
            }
            if (rawBatchSize <= 0) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        false
                );
                break;
            }
            int nextOffset;
            try {
                nextOffset = Math.addExact(offset, rawBatchSize);
            } catch (ArithmeticException exception) {
                accumulator.stop(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN, false);
                break;
            }
            if (nextOffset <= offset) {
                accumulator.stop(YagaShopDiscoveryStopReason.SOURCE_UNKNOWN, false);
                break;
            }
            offset = nextOffset;
        }

        enrichNewCandidates(accumulator);
        return accumulator.toResponse();
    }

    private void discoverPage(
            DiscoveryAccumulator accumulator,
            YagaShopPage page
    ) {
        for (YagaShopPage.ProductLink link : page.productLinks()) {
            accumulator.cardsDiscovered++;
            if (!accumulator.recordIdentity(link)) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.SOURCE_UNKNOWN,
                        false
                );
                return;
            }
            if (accumulator.uniqueCandidates.size() >=
                    properties.maxListings()) {
                accumulator.stop(
                        YagaShopDiscoveryStopReason.MAX_LISTINGS,
                        false
                );
                return;
            }
            collectCandidate(accumulator, link);
        }
    }

    private String pageFingerprint(YagaShopPage page, int rawBatchSize) {
        return rawBatchSize + ":" + page.productLinks().stream()
                .map(link -> value(link.externalListingId()) + ":" +
                        link.shopSlug() + "/" + link.productSlug())
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private YagaShopPage loadPublishedProductsPage(
            String shopSlug,
            long trustedShopId,
            int offset,
            int limit
    ) {
        try {
            return shopPageClient.getPublishedProductsPage(
                    shopSlug,
                    trustedShopId,
                    offset,
                    limit,
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
                    "Failed to load Yaga published product listings",
                    exception,
                    Map.of(
                            "stopReason",
                            YagaShopDiscoveryStopReason.SOURCE_UNKNOWN.name(),
                            "completenessConfirmed",
                            "false",
                            "offset",
                            String.valueOf(offset),
                            "limit",
                            String.valueOf(limit)
                    )
            );
        }
    }

    private YagaShopPage loadInitialShopPage(String shopSlug) {
        try {
            return shopPageClient.getPage(
                    shopSlug,
                    1,
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

    private void collectCandidate(
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

    }

    private void enrichNewCandidates(DiscoveryAccumulator accumulator) {
        List<YagaShopPage.ProductLink> newCandidates = new ArrayList<>();
        for (YagaShopPage.ProductLink link :
                accumulator.uniqueCandidates.values()) {
            if (!accumulator.shopSlug.equals(link.shopSlug()) ||
                    link.externalListingId() == null) {
                continue;
            }

            String externalId = link.externalListingId().toString();
            YagaShopDiscoveredListingResponse discovered = discovered(
                    link,
                    externalId,
                    null
            );
            if (exists(link, externalId)) {
                accumulator.activeExisting.add(discovered);
                continue;
            }
            newCandidates.add(link);
        }

        for (YagaShopPage.ProductLink link : newCandidates) {
            String externalId = link.externalListingId().toString();
            YagaImportedProductData data;
            try {
                delayBetweenRequests();
                data = pageDataClient.getProduct(link.publicUrl());
            } catch (RuntimeException exception) {
                accumulator.failed.add(
                        new YagaShopDiscoveryFailedResponse(
                                link.productSlug(),
                                YagaShopDiscoveryReason.FETCH_FAILED,
                                "Failed to load Yaga product detail"
                        )
                );
                continue;
            }

            Classification classification = classify(link, data);

            if (classification.skippedReason() != null) {
                accumulator.skipped.add(
                        new YagaShopDiscoverySkippedResponse(
                                link.productSlug(),
                                classification.skippedReason()
                        )
                );
                continue;
            }
            if (classification.failedReason() != null) {
                accumulator.failed.add(
                        new YagaShopDiscoveryFailedResponse(
                                link.productSlug(),
                                classification.failedReason(),
                                classification.safeMessage()
                        )
                );
                continue;
            }

            String title = titleResolver.resolve(data).orElse(null);
            if (title == null) {
                accumulator.failed.add(
                        new YagaShopDiscoveryFailedResponse(
                                link.productSlug(),
                                YagaShopDiscoveryReason.INVALID_DATA,
                                "Yaga listing title is missing or invalid"
                        )
                );
                continue;
            }
            accumulator.activeNew.add(discovered(link, externalId, title));
        }
    }

    private YagaShopDiscoveredListingResponse discovered(
            YagaShopPage.ProductLink link,
            String externalId,
            String title
    ) {
        return new YagaShopDiscoveredListingResponse(
                externalId,
                link.productSlug(),
                title,
                link.publicUrl(),
                link.externalCreatedAt(),
                link.imageCount()
        );
    }

    private boolean exists(
            YagaShopPage.ProductLink link,
            String externalId
    ) {
        boolean byExternalId =
                listingRepository.existsByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        externalId
                );
        boolean bySlug =
                listingRepository
                        .findByMarketplaceAndShopSlugAndProductSlug(
                                Marketplace.YAGA,
                                link.shopSlug(),
                                link.productSlug()
                        )
                        .isPresent();
        return byExternalId || bySlug;
    }

    private Classification classify(
            YagaShopPage.ProductLink link,
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
        if (!link.externalListingId().equals(data.externalId())) {
            return Classification.failed(
                    YagaShopDiscoveryReason.INVALID_DATA,
                    "Yaga product external ID mismatch"
            );
        }
        if (!link.shopSlug().equals(data.shopSlug())) {
            return Classification.failed(
                    YagaShopDiscoveryReason.INVALID_DATA,
                    "Yaga product shop mismatch"
            );
        }
        if (!link.productSlug().equals(data.productSlug())) {
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

        String status = data.status() == null ? "" : data.status();
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
        details.put(
                "initialItemCount",
                String.valueOf(diagnostics.initialItemCount())
        );
        details.put(
                "declaredTotal",
                value(diagnostics.declaredTotal())
        );
        details.put(
                "declaredTotalSource",
                value(diagnostics.declaredTotalSource())
        );
        details.put(
                "declaredTotalTrusted",
                String.valueOf(diagnostics.declaredTotalTrusted())
        );
        details.put(
                "rejectedTotalCandidates",
                diagnostics.rejectedTotalCandidates().toString()
        );
        details.put(
                "paginationFields",
                diagnostics.paginationFields().toString()
        );
        details.put(
                "hasNextPage",
                value(diagnostics.hasNextPage())
        );
        details.put("hasNextSource", value(diagnostics.hasNextSource()));
        details.put(
                "nextCursorAvailable",
                String.valueOf(diagnostics.nextCursorAvailable())
        );
        details.put(
                "nextRequestPath",
                value(diagnostics.nextRequestPath())
        );
        details.put(
                "continuationSource",
                value(diagnostics.continuationSource())
        );
        details.put(
                "candidateArraySource",
                value(diagnostics.candidateArraySource())
        );
        details.put(
                "trustedShopIdFound",
                String.valueOf(diagnostics.trustedShopIdFound())
        );
        details.put(
                "trustedShopIdSource",
                value(diagnostics.trustedShopIdSource())
        );
        details.put(
                "relevantRouteNames",
                diagnostics.relevantRouteNames().toString()
        );
        details.put(
                "stopReason",
                YagaShopDiscoveryStopReason.SOURCE_UNKNOWN.name()
        );
        details.put("completenessConfirmed", "false");
        return details;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
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
        private boolean completenessConfirmed;
        private YagaShopDiscoveryStopReason stopReason;
        private int initialItemCount;
        private Integer declaredTotal;
        private String declaredTotalSource;
        private boolean declaredTotalTrusted;
        private final List<Integer> totalsObserved = new ArrayList<>();
        private Integer minimumObservedTotal;
        private Integer maximumObservedTotal;
        private Integer finalObservedTotal;
        private int cumulativeRawCount;
        private final List<String> paginationInconsistencies =
                new ArrayList<>();
        private final List<String> duplicateIdentities = new ArrayList<>();
        private final Map<String, String> candidateKeyByExternalId =
                new LinkedHashMap<>();
        private final Map<String, String> externalIdByCandidateKey =
                new LinkedHashMap<>();
        private boolean conflictingDuplicateIdentity;
        private final Set<String> rejectedTotalCandidates =
                new LinkedHashSet<>();
        private final Set<String> paginationFields = new LinkedHashSet<>();
        private Boolean hasNextPage;
        private String hasNextSource;
        private boolean nextCursorAvailable;
        private String nextRequestPath;
        private String continuationSource;
        private String candidateArraySource;
        private final Set<String> relevantRouteNames = new LinkedHashSet<>();
        private boolean trustedShopIdFound;
        private final List<Integer> offsetsRequested = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();

        private DiscoveryAccumulator(String shopSlug) {
            this.shopSlug = shopSlug;
        }

        private void captureDiagnostics(YagaShopPage page, boolean initial) {
            YagaShopPageDiagnostics diagnostics = page.diagnostics();
            if (diagnostics == null) {
                return;
            }
            if (initial) {
                initialItemCount = diagnostics.initialItemCount();
                hasNextPage = diagnostics.hasNextPage();
                hasNextSource = diagnostics.hasNextSource();
                nextRequestPath = diagnostics.nextRequestPath();
                continuationSource = diagnostics.continuationSource();
                candidateArraySource = diagnostics.candidateArraySource();
                trustedShopIdFound = diagnostics.trustedShopIdFound();
                if (trustedShopIdFound) {
                    continuationSource = "confirmed:/api/product/";
                }
            } else {
                batchSizes.add(diagnostics.initialItemCount());
                candidateArraySource = diagnostics.candidateArraySource();
                if (diagnostics.declaredTotalTrusted() &&
                        "$.data.total".equals(
                                diagnostics.declaredTotalSource()
                        )) {
                    int total = diagnostics.declaredTotal();
                    totalsObserved.add(total);
                    minimumObservedTotal = minimumObservedTotal == null
                            ? total
                            : Math.min(minimumObservedTotal, total);
                    maximumObservedTotal = maximumObservedTotal == null
                            ? total
                            : Math.max(maximumObservedTotal, total);
                    finalObservedTotal = total;
                    declaredTotal = total;
                    declaredTotalSource = diagnostics.declaredTotalSource();
                    declaredTotalTrusted = true;
                }
            }
            if (initial && diagnostics.declaredTotalTrusted() &&
                    diagnostics.declaredTotal() != null &&
                    (declaredTotal == null ||
                            diagnostics.declaredTotal() > declaredTotal)) {
                declaredTotal = diagnostics.declaredTotal();
                declaredTotalSource = diagnostics.declaredTotalSource();
                declaredTotalTrusted = true;
            }
            rejectedTotalCandidates.addAll(
                    diagnostics.rejectedTotalCandidates()
            );
            paginationFields.addAll(diagnostics.paginationFields());
            nextCursorAvailable |= diagnostics.nextCursorAvailable();
            relevantRouteNames.addAll(diagnostics.relevantRouteNames());
        }

        private boolean observeRawBatch(int offset, int rawBatchSize) {
            if (offset != cumulativeRawCount || rawBatchSize < 0 ||
                    finalObservedTotal == null) {
                paginationInconsistencies.add(
                        "offset/raw sequence mismatch at offset=" + offset
                );
                return false;
            }
            cumulativeRawCount += rawBatchSize;
            if (finalObservedTotal < cumulativeRawCount) {
                paginationInconsistencies.add(
                        "$.data.total=" + finalObservedTotal +
                                " below cumulativeRawCount=" +
                                cumulativeRawCount
                );
                maximumObservedTotal = Math.max(
                        maximumObservedTotal,
                        cumulativeRawCount
                );
            }
            return true;
        }

        private boolean recordIdentity(YagaShopPage.ProductLink link) {
            if (link.externalListingId() == null) {
                return true;
            }
            String externalId = link.externalListingId().toString();
            String candidateKey = link.shopSlug() + "/" + link.productSlug();
            String knownKey = candidateKeyByExternalId.putIfAbsent(
                    externalId,
                    candidateKey
            );
            String knownExternalId = externalIdByCandidateKey.putIfAbsent(
                    candidateKey,
                    externalId
            );
            if (knownKey != null && !knownKey.equals(candidateKey) ||
                    knownExternalId != null &&
                            !knownExternalId.equals(externalId)) {
                conflictingDuplicateIdentity = true;
                paginationInconsistencies.add(
                        "conflicting product identity for " + candidateKey
                );
                return false;
            }
            if (knownKey != null) {
                duplicateIdentities.add(externalId + ":" + candidateKey);
            }
            return true;
        }

        private void stop(
                YagaShopDiscoveryStopReason reason,
                boolean confirmed
        ) {
            stopReason = reason;
            completenessConfirmed = confirmed;
            truncated = !confirmed;
        }

        private YagaShopDiscoveryResponse toResponse() {
            int activePublishedCount =
                    activeNew.size() + activeExisting.size();
            return new YagaShopDiscoveryResponse(
                    shopSlug,
                    "PUBLIC_API",
                    pagesVisited,
                    cardsDiscovered,
                    uniqueCandidates.size(),
                    activePublishedCount,
                    activeNew.size(),
                    activeExisting.size(),
                    skipped.size(),
                    failed.size(),
                    truncated,
                    completenessConfirmed,
                    stopReason,
                    initialItemCount,
                    declaredTotal,
                    declaredTotalSource,
                    declaredTotalTrusted,
                    List.copyOf(totalsObserved),
                    minimumObservedTotal,
                    maximumObservedTotal,
                    finalObservedTotal,
                    totalsObserved.stream().distinct().count() > 1,
                    cumulativeRawCount,
                    List.copyOf(paginationInconsistencies),
                    List.copyOf(duplicateIdentities),
                    List.copyOf(rejectedTotalCandidates),
                    List.copyOf(paginationFields),
                    hasNextPage,
                    hasNextSource,
                    nextCursorAvailable,
                    nextRequestPath,
                    continuationSource,
                    candidateArraySource,
                    List.copyOf(relevantRouteNames),
                    trustedShopIdFound,
                    List.copyOf(offsetsRequested),
                    List.copyOf(batchSizes),
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
