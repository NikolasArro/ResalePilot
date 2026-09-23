package ee.nikolas.resalepilot.marketplace.dto;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;

import java.util.List;

public record YagaListingReconciliationResponse(
        Long accountId,
        String shopSlug,
        int discoveredPublished,
        int localCurrentPublished,
        int unchanged,
        int sold,
        int hidden,
        int deleted,
        int unavailable,
        int remotePublishedAndLocalCurrentPublished,
        int remotePublishedButLocalNonCurrent,
        int remotePublishedButMissingLocally,
        List<RemotePublishedLocalDiagnostic> remotePublishedButLocalNonCurrentListings,
        List<RemotePublishedMissingLocalDiagnostic> remotePublishedButMissingLocalListings
) {

    public record RemotePublishedLocalDiagnostic(
            String externalListingId,
            String productSlug,
            MarketplaceListingStatus status,
            boolean current
    ) {
    }

    public record RemotePublishedMissingLocalDiagnostic(
            String externalListingId,
            String productSlug
    ) {
    }
}
