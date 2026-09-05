package ee.nikolas.resalepilot.workflow.yaga.importlisting.dto;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.product.dto.ProductResponse;
import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;

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