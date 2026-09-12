package ee.nikolas.resalepilot.workflow.yaga.shopimport.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaImportService;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.YagaProductTitleResolver;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.dto.YagaImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.exception.YagaImportConflictException;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopUrlBuilder;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.config.YagaShopImportProperties;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportItemResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItem;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItemStatus;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRun;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.exception.YagaShopImportRunNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class YagaShopImportService {

    private static final String CONFIRMATION_PHRASE = "IMPORT";

    private final YagaShopImportProperties properties;
    private final YagaShopDiscoveryService discoveryService;
    private final YagaPageDataClient pageDataClient;
    private final YagaImportService importService;
    private final YagaProductTitleResolver titleResolver;
    private final MarketplaceListingRepository listingRepository;
    private final ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportRunRepository runRepository;
    private final ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportItemRepository itemRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final YagaShopUrlBuilder urlBuilder =
            new YagaShopUrlBuilder();

    public YagaShopImportService(
            YagaShopImportProperties properties,
            YagaShopDiscoveryService discoveryService,
            YagaPageDataClient pageDataClient,
            YagaImportService importService,
            YagaProductTitleResolver titleResolver,
            MarketplaceListingRepository listingRepository,
            ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportRunRepository runRepository,
            ee.nikolas.resalepilot.workflow.yaga.shopimport.repository.YagaShopImportItemRepository itemRepository,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.pageDataClient = pageDataClient;
        this.importService = importService;
        this.titleResolver = titleResolver;
        this.listingRepository = listingRepository;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public YagaShopImportRunResponse prepare(
            String shopSlug,
            Integer requestedMaxItems,
            String idempotencyKey
    ) {
        urlBuilder.validateShopSlug(shopSlug);
        validateIdempotencyKey(idempotencyKey);
        int maxItems = resolveMaxItems(requestedMaxItems);

        Optional<YagaShopImportRun> existing =
                transactionTemplate.execute(status ->
                        runRepository.findByShopSlugAndIdempotencyKey(
                                shopSlug,
                                idempotencyKey
                        )
                );
        if (existing != null && existing.isPresent()) {
            return toResponse(existing.get());
        }

        YagaShopDiscoveryResponse discovery =
                discoveryService.discover(shopSlug);
        List<YagaShopDiscoveredListingResponse> selected =
                discovery.activeNew()
                        .stream()
                        .filter(listing -> titleResolver
                                .validate(listing.title())
                                .isPresent())
                        .limit(maxItems)
                        .toList();

        YagaShopImportRun saved = transactionTemplate.execute(status -> {
            Optional<YagaShopImportRun> concurrent =
                    runRepository.findByShopSlugAndIdempotencyKey(
                            shopSlug,
                            idempotencyKey
                    );
            if (concurrent.isPresent()) {
                return concurrent.get();
            }

            Instant now = clock.instant();
            YagaShopImportRun run = new YagaShopImportRun(
                    shopSlug,
                    idempotencyKey,
                    maxItems,
                    now
            );

            int order = 0;
            for (YagaShopDiscoveredListingResponse listing : selected) {
                Optional<String> selectedTitle =
                        titleResolver.validate(listing.title());
                run.addItem(
                        itemSnapshot(
                                order++,
                                listing,
                                shopSlug,
                                selectedTitle,
                                now
                        )
                );
            }
            run.setSelectedItemCount(selected.size());
            run.setStatus(YagaShopImportRunStatus.AWAITING_CONFIRMATION);
            return runRepository.saveAndFlush(run);
        });

        if (saved == null) {
            throw new IllegalStateException(
                    "Yaga shop import preparation returned no run"
            );
        }
        return get(saved.getId());
    }

    public YagaShopImportRunResponse get(UUID runId) {
        return runRepository.findWithItemsById(runId)
                .map(this::toResponse)
                .orElseThrow(() ->
                        new YagaShopImportRunNotFoundException(runId)
                );
    }

    public YagaShopImportRunResponse cancel(UUID runId) {
        YagaShopImportRun run = transactionTemplate.execute(status -> {
            YagaShopImportRun locked = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaShopImportRunNotFoundException(runId)
                    );
            if (locked.getStatus() !=
                    YagaShopImportRunStatus.AWAITING_CONFIRMATION) {
                throw new YagaShopImportInvalidStateException(
                        "Yaga shop import run cannot be cancelled after import started"
                );
            }
            locked.setStatus(YagaShopImportRunStatus.CANCELLED);
            locked.setCompletedAt(clock.instant());
            return runRepository.saveAndFlush(locked);
        });
        return get(run.getId());
    }

    public YagaShopImportRunResponse confirm(
            UUID runId,
            String confirmationPhrase
    ) {
        if (!CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new YagaShopImportRequestInvalidException(
                    "Confirmation phrase must be IMPORT"
            );
        }

        YagaShopImportRun run = markImportingOrReturnTerminal(runId);
        if (isTerminal(run.getStatus())) {
            return get(runId);
        }

        List<YagaShopImportItem> pending =
                itemRepository.findAllByRunIdOrderBySelectionOrderAsc(runId)
                        .stream()
                        .filter(item ->
                                item.getStatus() ==
                                        YagaShopImportItemStatus.SELECTED ||
                                        item.getStatus() ==
                                                YagaShopImportItemStatus.IMPORTING
                        )
                        .toList();

        for (YagaShopImportItem item : pending) {
            processItem(item.getId());
        }

        completeRun(runId);
        return get(runId);
    }

    private YagaShopImportRun markImportingOrReturnTerminal(UUID runId) {
        YagaShopImportRun run = transactionTemplate.execute(status -> {
            YagaShopImportRun locked = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaShopImportRunNotFoundException(runId)
                    );
            if (isTerminal(locked.getStatus())) {
                return locked;
            }
            if (locked.getStatus() !=
                    YagaShopImportRunStatus.AWAITING_CONFIRMATION &&
                    locked.getStatus() !=
                            YagaShopImportRunStatus.IMPORTING) {
                throw new YagaShopImportInvalidStateException(
                        "Yaga shop import run is not awaiting confirmation"
                );
            }
            locked.setStatus(YagaShopImportRunStatus.IMPORTING);
            if (locked.getStartedAt() == null) {
                locked.setStartedAt(clock.instant());
            }
            return runRepository.saveAndFlush(locked);
        });
        if (run == null) {
            throw new IllegalStateException("Yaga shop import run not loaded");
        }
        return run;
    }

    private void processItem(UUID itemId) {
        YagaShopImportItem snapshot =
                transactionTemplate.execute(status -> {
                    YagaShopImportItem item =
                            itemRepository.findById(itemId)
                                    .orElseThrow();
                    if (isTerminal(item.getStatus())) {
                        return item;
                    }
                    item.setStatus(YagaShopImportItemStatus.IMPORTING);
                    item.setStartedAt(clock.instant());
                    return itemRepository.saveAndFlush(item);
                });

        if (snapshot == null || isTerminal(snapshot.getStatus())) {
            return;
        }

        try {
            Optional<String> selectedTitle =
                    titleResolver.validate(snapshot.getSelectedTitle());
            if (selectedTitle.isEmpty()) {
                markInvalidData(
                        snapshot.getId(),
                        "INVALID_SELECTED_TITLE",
                        "Yaga shop import item has no valid selected title"
                );
                return;
            }

            Optional<MarketplaceListing> existing =
                    findExisting(snapshot);
            if (existing.isPresent()) {
                markExisting(snapshot.getId(), existing.get().getId());
                return;
            }

            YagaImportedProductData data =
                    pageDataClient.getProduct(snapshot.getPublicUrl());
            if (!isStillActive(snapshot, data)) {
                markSkipped(snapshot.getId(), "NOT_ACTIVE",
                        "Yaga listing is no longer active published");
                return;
            }

            Optional<String> currentTitle = titleResolver.resolve(data);
            if (currentTitle.isEmpty()) {
                markInvalidData(
                        snapshot.getId(),
                        "INVALID_TITLE",
                        "Yaga listing title is missing or invalid"
                );
                return;
            }
            if (!selectedTitle.get().equals(currentTitle.get())) {
                markInvalidData(
                        snapshot.getId(),
                        "TITLE_SNAPSHOT_MISMATCH",
                        "Yaga listing title changed after import preparation"
                );
                return;
            }

            existing = findExisting(data);
            if (existing.isPresent()) {
                markExisting(snapshot.getId(), existing.get().getId());
                return;
            }

            YagaImportResponse response =
                    importService.importFetchedProduct(
                            sku(data),
                            selectedTitle.get(),
                            null,
                            data
                    );
            markImported(
                    snapshot.getId(),
                    response.product().id(),
                    response.marketplaceListingId()
            );
        } catch (YagaImportConflictException exception) {
            Optional<MarketplaceListing> existing =
                    findExisting(snapshot);
            if (existing.isPresent()) {
                markExisting(snapshot.getId(), existing.get().getId());
                return;
            }
            markFailed(
                    snapshot.getId(),
                    "IMPORT_CONFLICT",
                    safeMessage(exception)
            );
        } catch (RuntimeException exception) {
            markFailed(
                    snapshot.getId(),
                    "IMPORT_FAILED",
                    safeMessage(exception)
            );
        }
    }

    private Optional<MarketplaceListing> findExisting(
            YagaShopImportItem item
    ) {
        if (item.getExternalListingId() != null) {
            Optional<MarketplaceListing> byExternalId =
                    listingRepository.findByMarketplaceAndExternalListingId(
                            Marketplace.YAGA,
                            item.getExternalListingId()
                    );
            if (byExternalId.isPresent()) {
                return byExternalId;
            }
        }
        return listingRepository.findByMarketplaceAndShopSlugAndProductSlug(
                Marketplace.YAGA,
                item.getShopSlug(),
                item.getProductSlug()
        );
    }

    private Optional<MarketplaceListing> findExisting(
            YagaImportedProductData data
    ) {
        Optional<MarketplaceListing> byExternalId =
                listingRepository.findByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        data.externalId().toString()
                );
        if (byExternalId.isPresent()) {
            return byExternalId;
        }
        return listingRepository.findByMarketplaceAndShopSlugAndProductSlug(
                Marketplace.YAGA,
                data.shopSlug(),
                data.productSlug()
        );
    }

    private boolean isStillActive(
            YagaShopImportItem item,
            YagaImportedProductData data
    ) {
        return data != null &&
                data.externalId() != null &&
                data.externalId().toString()
                        .equals(item.getExternalListingId()) &&
                item.getShopSlug().equals(data.shopSlug()) &&
                item.getProductSlug().equals(data.productSlug()) &&
                data.hiddenAt() == null &&
                data.deletedAt() == null &&
                "published".equals(data.status());
    }

    private void markExisting(
            UUID itemId,
            Long listingId
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportItem item = itemRepository.findById(itemId)
                    .orElseThrow();
            MarketplaceListing listing = listingRepository
                    .findById(listingId)
                    .orElseThrow();
            item.setStatus(YagaShopImportItemStatus.ALREADY_EXISTS);
            item.setProduct(listing.getProduct());
            item.setMarketplaceListing(listing);
            item.setCompletedAt(clock.instant());
            itemRepository.saveAndFlush(item);
        });
    }

    private void markSkipped(
            UUID itemId,
            String errorCode,
            String message
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportItem item = itemRepository.findById(itemId)
                    .orElseThrow();
            item.setStatus(YagaShopImportItemStatus.SKIPPED_NOT_ACTIVE);
            item.setLastErrorCode(errorCode);
            item.setLastSafeErrorMessage(message);
            item.setCompletedAt(clock.instant());
            itemRepository.saveAndFlush(item);
        });
    }

    private void markInvalidData(
            UUID itemId,
            String errorCode,
            String message
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportItem item = itemRepository.findById(itemId)
                    .orElseThrow();
            item.setStatus(YagaShopImportItemStatus.SKIPPED_INVALID_DATA);
            item.setLastErrorCode(errorCode);
            item.setLastSafeErrorMessage(message);
            item.setCompletedAt(clock.instant());
            itemRepository.saveAndFlush(item);
        });
    }

    private void markImported(
            UUID itemId,
            Long productId,
            Long listingId
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportItem item = itemRepository.findById(itemId)
                    .orElseThrow();
            MarketplaceListing listing = listingRepository
                    .findById(listingId)
                    .orElseThrow();
            item.setStatus(YagaShopImportItemStatus.IMPORTED);
            item.setProduct(listing.getProduct());
            item.setMarketplaceListing(listing);
            item.setCompletedAt(clock.instant());
            itemRepository.saveAndFlush(item);
        });
    }

    private void markFailed(
            UUID itemId,
            String errorCode,
            String message
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportItem item = itemRepository.findById(itemId)
                    .orElseThrow();
            item.setStatus(YagaShopImportItemStatus.FAILED);
            item.setLastErrorCode(errorCode);
            item.setLastSafeErrorMessage(message);
            item.setCompletedAt(clock.instant());
            itemRepository.saveAndFlush(item);
        });
    }

    private void completeRun(UUID runId) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaShopImportRun run = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaShopImportRunNotFoundException(runId)
                    );
            List<YagaShopImportItem> items =
                    itemRepository.findAllByRunIdOrderBySelectionOrderAsc(
                            runId
                    );
            int imported = 0;
            int existing = 0;
            int skipped = 0;
            int failed = 0;
            for (YagaShopImportItem item : items) {
                switch (item.getStatus()) {
                    case IMPORTED -> imported++;
                    case ALREADY_EXISTS -> existing++;
                    case SKIPPED_NOT_ACTIVE, SKIPPED_INVALID_DATA -> skipped++;
                    case FAILED -> failed++;
                    default -> {
                    }
                }
            }
            run.setImportedCount(imported);
            run.setExistingCount(existing);
            run.setSkippedCount(skipped);
            run.setFailedCount(failed);
            run.setCompletedAt(clock.instant());
            run.setStatus(failed > 0
                    ? YagaShopImportRunStatus.COMPLETED_WITH_ERRORS
                    : YagaShopImportRunStatus.COMPLETED);
            runRepository.saveAndFlush(run);
        });
    }

    private int resolveMaxItems(Integer requestedMaxItems) {
        int resolved = requestedMaxItems == null
                ? properties.defaultMaxItems()
                : requestedMaxItems;
        if (resolved < 1) {
            throw new YagaShopImportRequestInvalidException(
                    "maxItems must be at least 1"
            );
        }
        if (resolved > properties.maxItems()) {
            throw new YagaShopImportRequestInvalidException(
                    "maxItems must not exceed configured max-items"
            );
        }
        return resolved;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new YagaShopImportRequestInvalidException(
                    "idempotencyKey is required"
            );
        }
        if (idempotencyKey.length() > 120) {
            throw new YagaShopImportRequestInvalidException(
                    "idempotencyKey must be at most 120 characters"
            );
        }
    }

    private boolean isTerminal(YagaShopImportRunStatus status) {
        return status == YagaShopImportRunStatus.COMPLETED ||
                status == YagaShopImportRunStatus.COMPLETED_WITH_ERRORS ||
                status == YagaShopImportRunStatus.FAILED ||
                status == YagaShopImportRunStatus.CANCELLED;
    }

    private boolean isTerminal(YagaShopImportItemStatus status) {
        return status == YagaShopImportItemStatus.IMPORTED ||
                status == YagaShopImportItemStatus.ALREADY_EXISTS ||
                status == YagaShopImportItemStatus.SKIPPED_NOT_ACTIVE ||
                status == YagaShopImportItemStatus.SKIPPED_INVALID_DATA ||
                status == YagaShopImportItemStatus.FAILED;
    }

    private String sku(YagaImportedProductData data) {
        return "YAGA-" + data.externalId();
    }

    private YagaShopImportItem itemSnapshot(
            int selectionOrder,
            YagaShopDiscoveredListingResponse listing,
            String shopSlug,
            Optional<String> selectedTitle,
            Instant now
    ) {
        YagaShopImportItem item = new YagaShopImportItem(
                selectionOrder,
                listing.externalListingId(),
                shopSlug,
                listing.productSlug(),
                selectedTitle.orElse(null),
                listing.publicUrl(),
                "published",
                listing.externalCreatedAt(),
                listing.imageCount(),
                now
        );

        if (selectedTitle.isEmpty()) {
            item.setStatus(YagaShopImportItemStatus.SKIPPED_INVALID_DATA);
            item.setLastErrorCode("INVALID_SELECTED_TITLE");
            item.setLastSafeErrorMessage(
                    "Yaga listing title is missing or invalid"
            );
            item.setCompletedAt(now);
        }

        return item;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private YagaShopImportRunResponse toResponse(YagaShopImportRun run) {
        List<YagaShopImportItemResponse> items =
                run.getItems()
                        .stream()
                        .sorted(Comparator.comparingInt(
                                YagaShopImportItem::getSelectionOrder
                        ))
                        .map(this::toResponse)
                        .toList();

        return new YagaShopImportRunResponse(
                run.getId(),
                run.getShopSlug(),
                run.getStatus(),
                run.getRequestedMaxItems(),
                run.getSelectedItemCount(),
                run.getImportedCount(),
                run.getExistingCount(),
                run.getSkippedCount(),
                run.getFailedCount(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                items
        );
    }

    private YagaShopImportItemResponse toResponse(
            YagaShopImportItem item
    ) {
        return new YagaShopImportItemResponse(
                item.getId(),
                item.getSelectionOrder(),
                item.getExternalListingId(),
                item.getProductSlug(),
                item.getSelectedTitle(),
                item.getPublicUrl(),
                item.getExternalCreatedAt(),
                item.getExpectedImageCount(),
                item.getStatus(),
                item.getProduct() == null ? null : item.getProduct().getId(),
                item.getMarketplaceListing() == null
                        ? null
                        : item.getMarketplaceListing().getId(),
                item.getLastErrorCode(),
                item.getLastSafeErrorMessage()
        );
    }
}
