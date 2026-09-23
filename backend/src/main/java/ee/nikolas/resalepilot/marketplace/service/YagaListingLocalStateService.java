package ee.nikolas.resalepilot.marketplace.service;

import ee.nikolas.resalepilot.marketplace.dto.MarketplaceListingStatusResponse;
import ee.nikolas.resalepilot.marketplace.dto.YagaListingReconciliationResponse;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingStatusInvalidException;
import ee.nikolas.resalepilot.marketplace.exception.YagaAccountNotFoundException;
import ee.nikolas.resalepilot.marketplace.exception.YagaListingReconciliationIncompleteException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountRepository;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveredListingResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class YagaListingLocalStateService {

    private static final EnumSet<MarketplaceListingStatus>
            LOCAL_NON_ACTIVE_STATUSES = EnumSet.of(
                    MarketplaceListingStatus.SOLD,
                    MarketplaceListingStatus.HIDDEN,
                    MarketplaceListingStatus.DELETED,
                    MarketplaceListingStatus.UNAVAILABLE
            );

    private final YagaAccountRepository accountRepository;
    private final MarketplaceListingRepository listingRepository;
    private final YagaShopDiscoveryService discoveryService;
    private final Clock clock;

    public YagaListingLocalStateService(
            YagaAccountRepository accountRepository,
            MarketplaceListingRepository listingRepository,
            YagaShopDiscoveryService discoveryService,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.listingRepository = listingRepository;
        this.discoveryService = discoveryService;
        this.clock = clock;
    }

    @Transactional
    public MarketplaceListingStatusResponse updateStatusBySlug(
            Long accountId,
            String productSlug,
            MarketplaceListingStatus status
    ) {
        requireLocalNonActive(status);
        requireAccount(accountId);

        MarketplaceListing listing = listingRepository
                .findByYagaAccountIdAndMarketplaceAndProductSlug(
                        accountId,
                        Marketplace.YAGA,
                        productSlug
                )
                .orElseThrow(() ->
                        new MarketplaceListingNotFoundException(productSlug));

        applyLocalNonActiveStatus(listing, status, clock.instant());
        return response(listingRepository.saveAndFlush(listing));
    }

    @Transactional
    public YagaListingReconciliationResponse reconcile(Long accountId) {
        YagaAccount account = requireAccount(accountId);
        YagaShopDiscoveryResponse discovery =
                discoveryService.discover(account.getShopSlug());

        if (!discovery.completenessConfirmed()) {
            throw new YagaListingReconciliationIncompleteException(
                    account.getShopSlug());
        }

        Map<String, YagaShopDiscoveredListingResponse> remoteListings =
                remoteListingsByExternalId(discovery);
        Set<String> remoteExternalIds = remoteListings.keySet();
        List<MarketplaceListing> currentPublished =
                listingRepository
                        .findAllByYagaAccountIdAndMarketplaceAndStatusAndCurrentTrue(
                                accountId,
                                Marketplace.YAGA,
                                MarketplaceListingStatus.PUBLISHED
                        );
        List<MarketplaceListing> accountListings =
                listingRepository.findAllByYagaAccountIdAndMarketplace(
                        accountId,
                        Marketplace.YAGA
                );
        ReconciliationDiagnostics diagnostics =
                diagnostics(remoteListings, accountListings);

        Instant now = clock.instant();
        int unchanged = 0;
        int unavailable = 0;
        for (MarketplaceListing listing : currentPublished) {
            if (remoteExternalIds.contains(listing.getExternalListingId())) {
                unchanged++;
                continue;
            }
            applyLocalNonActiveStatus(
                    listing,
                    MarketplaceListingStatus.UNAVAILABLE,
                    now
            );
            unavailable++;
        }

        if (unavailable > 0) {
            listingRepository.saveAll(currentPublished);
            listingRepository.flush();
        }

        return new YagaListingReconciliationResponse(
                accountId,
                account.getShopSlug(),
                remoteExternalIds.size(),
                currentPublished.size(),
                unchanged,
                0,
                0,
                0,
                unavailable,
                diagnostics.remotePublishedAndLocalCurrentPublished(),
                diagnostics.remotePublishedButLocalNonCurrentListings().size(),
                diagnostics.remotePublishedButMissingLocalListings().size(),
                diagnostics.remotePublishedButLocalNonCurrentListings(),
                diagnostics.remotePublishedButMissingLocalListings()
        );
    }

    private YagaAccount requireAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new YagaAccountNotFoundException(accountId));
    }

    private void requireLocalNonActive(
            MarketplaceListingStatus status
    ) {
        if (!LOCAL_NON_ACTIVE_STATUSES.contains(status)) {
            throw new MarketplaceListingStatusInvalidException(status);
        }
    }

    private Map<String, YagaShopDiscoveredListingResponse>
    remoteListingsByExternalId(YagaShopDiscoveryResponse discovery) {
        Map<String, YagaShopDiscoveredListingResponse> listings =
                new LinkedHashMap<>();
        addRemoteListings(listings, discovery.activeNew());
        addRemoteListings(listings, discovery.activeExisting());
        return listings;
    }

    private void addRemoteListings(
            Map<String, YagaShopDiscoveredListingResponse> listings,
            List<YagaShopDiscoveredListingResponse> discovered
    ) {
        for (YagaShopDiscoveredListingResponse listing : discovered) {
            if (listing.externalListingId() != null &&
                    !listing.externalListingId().isBlank()) {
                listings.putIfAbsent(listing.externalListingId(), listing);
            }
        }
    }

    private ReconciliationDiagnostics diagnostics(
            Map<String, YagaShopDiscoveredListingResponse> remoteListings,
            List<MarketplaceListing> localListings
    ) {
        Map<String, MarketplaceListing> localByExternalId =
                new LinkedHashMap<>();
        for (MarketplaceListing listing : localListings) {
            if (listing.getExternalListingId() != null &&
                    !listing.getExternalListingId().isBlank()) {
                localByExternalId.putIfAbsent(
                        listing.getExternalListingId(),
                        listing
                );
            }
        }

        int localCurrentPublished = 0;
        List<YagaListingReconciliationResponse.RemotePublishedLocalDiagnostic>
                localNonCurrent = new java.util.ArrayList<>();
        List<YagaListingReconciliationResponse.RemotePublishedMissingLocalDiagnostic>
                missingLocal = new java.util.ArrayList<>();

        for (YagaShopDiscoveredListingResponse remote
                : remoteListings.values()) {
            MarketplaceListing local =
                    localByExternalId.get(remote.externalListingId());
            if (local == null) {
                missingLocal.add(
                        new YagaListingReconciliationResponse
                                .RemotePublishedMissingLocalDiagnostic(
                                remote.externalListingId(),
                                remote.productSlug()
                        )
                );
                continue;
            }
            if (local.getStatus() == MarketplaceListingStatus.PUBLISHED &&
                    local.isCurrent()) {
                localCurrentPublished++;
                continue;
            }
            localNonCurrent.add(
                    new YagaListingReconciliationResponse
                            .RemotePublishedLocalDiagnostic(
                            local.getExternalListingId(),
                            local.getProductSlug(),
                            local.getStatus(),
                            local.isCurrent()
                    )
            );
        }

        return new ReconciliationDiagnostics(
                localCurrentPublished,
                localNonCurrent,
                missingLocal
        );
    }

    private void applyLocalNonActiveStatus(
            MarketplaceListing listing,
            MarketplaceListingStatus status,
            Instant now
    ) {
        listing.setStatus(status);
        listing.setCurrent(false);
        listing.setLastSyncedAt(now);
        if (status == MarketplaceListingStatus.HIDDEN &&
                listing.getHiddenAt() == null) {
            listing.setHiddenAt(now);
        }
        if (status == MarketplaceListingStatus.DELETED &&
                listing.getDeletedAt() == null) {
            listing.setDeletedAt(now);
        }
    }

    private MarketplaceListingStatusResponse response(
            MarketplaceListing listing
    ) {
        return new MarketplaceListingStatusResponse(
                listing.getYagaAccount().getId(),
                listing.getId(),
                listing.getExternalListingId(),
                listing.getProductSlug(),
                listing.getStatus(),
                listing.isCurrent()
        );
    }

    private record ReconciliationDiagnostics(
            int remotePublishedAndLocalCurrentPublished,
            List<YagaListingReconciliationResponse.RemotePublishedLocalDiagnostic>
                    remotePublishedButLocalNonCurrentListings,
            List<YagaListingReconciliationResponse.RemotePublishedMissingLocalDiagnostic>
                    remotePublishedButMissingLocalListings
    ) {
    }
}
