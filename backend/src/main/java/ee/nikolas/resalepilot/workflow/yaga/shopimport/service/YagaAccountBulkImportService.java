package ee.nikolas.resalepilot.workflow.yaga.shopimport.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveService;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaImportService;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaImportService.YagaImportUpsertResult;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaAccountBulkImportFailureResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaAccountBulkImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportRequestInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class YagaAccountBulkImportService {

    private static final Logger log =
            LoggerFactory.getLogger(YagaAccountBulkImportService.class);

    private final YagaAccountService accountService;
    private final YagaShopDiscoveryService discoveryService;
    private final YagaPageDataClient pageDataClient;
    private final YagaImportService importService;
    private final YagaImageArchiveService archiveService;

    public YagaAccountBulkImportService(
            YagaAccountService accountService,
            YagaShopDiscoveryService discoveryService,
            YagaPageDataClient pageDataClient,
            YagaImportService importService,
            YagaImageArchiveService archiveService
    ) {
        this.accountService = accountService;
        this.discoveryService = discoveryService;
        this.pageDataClient = pageDataClient;
        this.importService = importService;
        this.archiveService = archiveService;
    }

    public YagaAccountBulkImportResponse importCurrentListings(
            Long accountId
    ) {
        return importCurrentListings(accountId, null);
    }

    public YagaAccountBulkImportResponse importCurrentListings(
            Long accountId,
            Integer limit
    ) {
        return importCurrentListings(accountId, limit, 0);
    }

    public YagaAccountBulkImportResponse importCurrentListings(
            Long accountId,
            Integer limit,
            Integer offset
    ) {
        return importCurrentListings(accountId, limit, offset, false);
    }

    public YagaAccountBulkImportResponse importCurrentListings(
            Long accountId,
            Integer limit,
            Integer offset,
            Boolean onlyNew
    ) {
        validateLimit(limit);
        int normalizedOffset = validateAndNormalizeOffset(offset);
        boolean onlyNewListings = Boolean.TRUE.equals(onlyNew);
        YagaAccount account = accountService.getEntity(accountId);
        if (!account.isEnabled()) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga account is disabled"
            );
        }

        log.info(
                "Yaga account bulk import started: accountId={} shopSlug={} requestedLimit={} requestedOffset={} onlyNew={}",
                account.getId(),
                account.getShopSlug(),
                limit,
                normalizedOffset,
                onlyNewListings
        );
        log.info(
                "Yaga account bulk import discovery started: accountId={} shopSlug={}",
                account.getId(),
                account.getShopSlug()
        );
        Instant discoveryStarted = Instant.now();
        Integer discoveryDetailLimit =
                detailEnrichmentLimit(limit, normalizedOffset);
        YagaShopDiscoveryResponse discovery;
        if (limit == null && !onlyNewListings) {
            discovery = discoveryService.discover(account.getShopSlug());
        } else if (onlyNewListings) {
            discovery = discoveryService.discover(
                    account.getShopSlug(),
                    discoveryDetailLimit,
                    onlyNewListings
            );
        } else {
            discovery = discoveryService.discover(
                    account.getShopSlug(),
                    discoveryDetailLimit
            );
        }
        long discoveryElapsedMs = elapsedMillis(discoveryStarted);
        log.info(
                "Yaga account bulk import discovery completed: accountId={} shopSlug={} discoveredTotal={} processableCount={} completenessConfirmed={} stopReason={} elapsedMs={}",
                account.getId(),
                account.getShopSlug(),
                discovery.uniqueCandidates(),
                discovery.activeNew().size() + discovery.activeExisting().size(),
                discovery.completenessConfirmed(),
                discovery.stopReason(),
                discoveryElapsedMs
        );
        if (!discovery.completenessConfirmed()) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga shop discovery did not confirm a complete listing set"
            );
        }

        List<YagaShopDiscoveredListingResponse> listings =
                discoveredListings(discovery);
        int discoveredTotal = discovery.uniqueCandidates();
        List<YagaShopDiscoveredListingResponse> listingsToProcess =
                applyOffsetAndLimit(listings, normalizedOffset, limit);

        log.info(
                "Yaga account bulk import discovered listings: accountId={} shopSlug={} discoveredTotal={} processedCount={} offset={} limit={}",
                account.getId(),
                account.getShopSlug(),
                discoveredTotal,
                listingsToProcess.size(),
                normalizedOffset,
                limit
        );

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<YagaAccountBulkImportFailureResponse> failures =
                new ArrayList<>();

        for (int index = 0; index < listingsToProcess.size(); index++) {
            YagaShopDiscoveredListingResponse listing =
                    listingsToProcess.get(index);
            try {
                ProcessResult result =
                        processListing(account, listing, index + 1,
                                listingsToProcess.size());
                if (result == ProcessResult.CREATED) {
                    created++;
                } else if (result == ProcessResult.UPDATED) {
                    updated++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failures.add(new YagaAccountBulkImportFailureResponse(
                        listing.externalListingId(),
                        listing.productSlug(),
                        "IMPORT_FAILED",
                        safeMessage(exception)
                ));
                log.warn(
                        "Yaga account bulk import listing failed: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} errorCode={}",
                        account.getId(),
                        account.getShopSlug(),
                        index + 1,
                        listingsToProcess.size(),
                        listing.externalListingId(),
                        listing.productSlug(),
                        "IMPORT_FAILED"
                );
            }
        }

        log.info(
                "Yaga account bulk import completed: accountId={} shopSlug={} discoveredTotal={} processedCount={} created={} updated={} skipped={} failed={}",
                account.getId(),
                account.getShopSlug(),
                discoveredTotal,
                listingsToProcess.size(),
                created,
                updated,
                skipped,
                failures.size()
        );

        return new YagaAccountBulkImportResponse(
                account.getId(),
                account.getShopSlug(),
                discoveredTotal,
                discoveredTotal,
                listingsToProcess.size(),
                created,
                updated,
                skipped,
                failures.size(),
                List.copyOf(failures)
        );
    }

    private void validateLimit(Integer limit) {
        if (limit != null && limit <= 0) {
            throw new YagaShopImportRequestInvalidException(
                    "limit must be greater than 0"
            );
        }
    }

    private int validateAndNormalizeOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new YagaShopImportRequestInvalidException(
                    "offset must be greater than or equal to 0"
            );
        }
        return offset;
    }

    private Integer detailEnrichmentLimit(Integer limit, int offset) {
        if (limit == null) {
            return null;
        }
        try {
            return Math.addExact(offset, limit);
        } catch (ArithmeticException exception) {
            throw new YagaShopImportRequestInvalidException(
                    "offset plus limit is too large"
            );
        }
    }

    private List<YagaShopDiscoveredListingResponse> applyOffsetAndLimit(
            List<YagaShopDiscoveredListingResponse> listings,
            int offset,
            Integer limit
    ) {
        if (offset >= listings.size()) {
            return List.of();
        }
        int end = limit == null
                ? listings.size()
                : Math.min(listings.size(), offset + limit);
        return List.copyOf(listings.subList(offset, end));
    }

    private ProcessResult processListing(
            YagaAccount account,
            YagaShopDiscoveredListingResponse discovered,
            int processed,
            int total
    ) {
        log.info(
                "Yaga account bulk import listing started: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug()
        );

        Instant detailStarted = Instant.now();
        YagaImportedProductData data =
                pageDataClient.getProduct(discovered.publicUrl());
        log.info(
                "Yaga account bulk import detail fetch completed: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} elapsedMs={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug(),
                elapsedMillis(detailStarted)
        );
        validateFetchedListing(account, discovered, data);

        if (!isActivePublished(data)) {
            log.info(
                    "Yaga account bulk import listing skipped: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} reason=not-active",
                    account.getId(),
                    account.getShopSlug(),
                    processed,
                    total,
                    discovered.externalListingId(),
                    discovered.productSlug()
            );
            return ProcessResult.SKIPPED;
        }

        Instant upsertStarted = Instant.now();
        YagaImportUpsertResult result =
                importService.importOrUpdateFetchedProduct(
                        account,
                        null,
                        data
                );
        log.info(
                "Yaga account bulk import DB import/upsert completed: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} marketplaceListingId={} created={} elapsedMs={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug(),
                result.response().marketplaceListingId(),
                result.created(),
                elapsedMillis(upsertStarted)
        );

        log.info(
                "Yaga account bulk import image archive started: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} marketplaceListingId={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug(),
                result.response().marketplaceListingId()
        );
        Instant archiveStarted = Instant.now();
        archiveService.archiveImages(result.response().marketplaceListingId());
        log.info(
                "Yaga account bulk import image archive completed: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} marketplaceListingId={} elapsedMs={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug(),
                result.response().marketplaceListingId(),
                elapsedMillis(archiveStarted)
        );

        log.info(
                "Yaga account bulk import listing completed: accountId={} shopSlug={} processed={}/{} externalListingId={} productSlug={} created={}",
                account.getId(),
                account.getShopSlug(),
                processed,
                total,
                discovered.externalListingId(),
                discovered.productSlug(),
                result.created()
        );

        return result.created()
                ? ProcessResult.CREATED
                : ProcessResult.UPDATED;
    }

    private List<YagaShopDiscoveredListingResponse> discoveredListings(
            YagaShopDiscoveryResponse discovery
    ) {
        Map<String, YagaShopDiscoveredListingResponse> byIdentity =
                new LinkedHashMap<>();
        for (YagaShopDiscoveredListingResponse listing :
                discovery.activeNew()) {
            byIdentity.put(identity(listing), listing);
        }
        for (YagaShopDiscoveredListingResponse listing :
                discovery.activeExisting()) {
            byIdentity.putIfAbsent(identity(listing), listing);
        }
        return List.copyOf(byIdentity.values());
    }

    private String identity(YagaShopDiscoveredListingResponse listing) {
        if (listing.externalListingId() != null &&
                !listing.externalListingId().isBlank()) {
            return "external:" + listing.externalListingId();
        }
        return "slug:" + listing.productSlug();
    }

    private void validateFetchedListing(
            YagaAccount account,
            YagaShopDiscoveredListingResponse discovered,
            YagaImportedProductData data
    ) {
        if (data == null ||
                data.externalId() == null ||
                data.shopSlug() == null ||
                data.productSlug() == null) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga listing data is incomplete"
            );
        }
        if (!account.getShopSlug().equals(data.shopSlug())) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga listing belongs to another shop"
            );
        }
        if (discovered.externalListingId() != null &&
                !discovered.externalListingId()
                        .equals(data.externalId().toString())) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga listing external ID changed during import"
            );
        }
        if (!discovered.productSlug().equals(data.productSlug())) {
            throw new YagaShopImportRequestInvalidException(
                    "Yaga listing product slug changed during import"
            );
        }
    }

    private boolean isActivePublished(YagaImportedProductData data) {
        return data.hiddenAt() == null &&
                data.deletedAt() == null &&
                "published".equals(data.status());
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private long elapsedMillis(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private enum ProcessResult {
        CREATED,
        UPDATED,
        SKIPPED
    }
}
