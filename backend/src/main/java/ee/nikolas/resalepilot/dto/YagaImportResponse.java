package ee.nikolas.resalepilot.dto;

import ee.nikolas.resalepilot.entity.MarketplaceListingStatus;

import java.util.List;

public record YagaImportResponse(
        ProductResponse product,
        Long marketplaceListingId,
        String externalListingId,
        String externalUrl,
        MarketplaceListingStatus listingStatus,
        List<String> categoryPath,
        int externalImageCount
) {
}