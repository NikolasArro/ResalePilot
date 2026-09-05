package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductStatus;

import ee.nikolas.resalepilot.product.dto.ProductResponse;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.dto.YagaImportRequest;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.dto.YagaImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.importlisting.exception.YagaImportConflictException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class YagaImportService {

    private final YagaPageDataClient pageDataClient;
    private final ProductRepository productRepository;
    private final MarketplaceListingRepository listingRepository;
    private final TransactionTemplate transactionTemplate;

    public YagaImportService(
            YagaPageDataClient pageDataClient,
            ProductRepository productRepository,
            MarketplaceListingRepository listingRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.pageDataClient = pageDataClient;
        this.productRepository = productRepository;
        this.listingRepository = listingRepository;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    public YagaImportResponse importProduct(
            YagaImportRequest request
    ) {
        /*
         * Сетевой запрос выполняется до начала транзакции,
         * чтобы не удерживать соединение с БД.
         */
        YagaImportedProductData data =
                pageDataClient.getProduct(request.productUrl());

        YagaImportResponse response =
                transactionTemplate.execute(status ->
                        persistImport(request, data)
                );

        if (response == null) {
            throw new IllegalStateException(
                    "Yaga import transaction returned no result"
            );
        }

        return response;
    }

    private YagaImportResponse persistImport(
            YagaImportRequest request,
            YagaImportedProductData data
    ) {
        String externalListingId =
                data.externalId().toString();

        if (productRepository.existsBySku(request.sku())) {
            throw new YagaImportConflictException(
                    "Product SKU already exists: " +
                            request.sku()
            );
        }

        if (listingRepository
                .existsByMarketplaceAndExternalListingId(
                        Marketplace.YAGA,
                        externalListingId
                )) {

            throw new YagaImportConflictException(
                    "Yaga listing is already imported: " +
                            externalListingId
            );
        }

        MarketplaceListingStatus listingStatus =
                mapListingStatus(data);

        Product product = new Product(
                request.sku(),
                request.title()
        );

        product.setDescription(data.description());
        product.setAskingPrice(data.price());
        product.setCondition(
                resolveProductCondition(
                        data,
                        request.conditionOverride()
                )
        );
        product.setCategory(
                findLeafCategory(data)
        );
        product.setStatus(
                mapProductStatus(listingStatus)
        );

        productRepository.save(product);

        String canonicalUrl = String.format(
                "https://www.yaga.ee/%s/toode/%s",
                data.shopSlug(),
                data.productSlug()
        );

        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        externalListingId,
                        canonicalUrl
                );

        listing.setShopSlug(data.shopSlug());
        listing.setProductSlug(data.productSlug());
        listing.setStatus(listingStatus);
        listing.setExternalStatus(data.status());
        listing.setCurrent(true);
        listing.setExternalCreatedAt(data.createdAt());
        listing.setExternalUpdatedAt(data.updatedAt());
        listing.setHiddenAt(data.hiddenAt());
        listing.setDeletedAt(data.deletedAt());
        listing.setLastSyncedAt(Instant.now());

        if (data.condition() != null) {
            listing.setExternalConditionId(
                    data.condition().id()
            );
            listing.setExternalConditionName(
                    data.condition().name()
            );
        }

        for (int level = 0;
             level < data.categoryPath().size();
             level++) {

            YagaImportedProductData.Category category =
                    data.categoryPath().get(level);

            listing.addCategory(
                    new MarketplaceListingCategory(
                            level,
                            category.id(),
                            category.parentId(),
                            category.title()
                    )
            );
        }

        for (int order = 0;
             order < data.images().size();
             order++) {

            YagaImportedProductData.Image image =
                    data.images().get(order);

            listing.addImage(
                    new MarketplaceListingImage(
                            image.id(),
                            image.originalUrl(),
                            image.fileName(),
                            order
                    )
            );
        }

        MarketplaceListing savedListing =
                listingRepository.saveAndFlush(listing);

        List<String> categoryPath =
                savedListing.getCategories()
                        .stream()
                        .map(MarketplaceListingCategory::getTitle)
                        .toList();

        return new YagaImportResponse(
                ProductResponse.from(product),
                savedListing.getId(),
                savedListing.getExternalListingId(),
                savedListing.getExternalUrl(),
                savedListing.getStatus(),
                categoryPath,
                savedListing.getImages().size()
        );
    }

    private ProductCondition resolveProductCondition(
            YagaImportedProductData data,
            ProductCondition override
    ) {
        if (override != null) {
            return override;
        }

        if (data.condition() == null ||
                data.condition().id() == null) {
            return null;
        }

        return switch (data.condition().id().intValue()) {
            case 1 -> ProductCondition.NEW_WITHOUT_TAGS;
            case 2 -> ProductCondition.VERY_GOOD;
            case 3 -> ProductCondition.GOOD;
            case 4 -> ProductCondition.SATISFACTORY;

            default -> null;
        };
    }

    private MarketplaceListingStatus mapListingStatus(
            YagaImportedProductData data
    ) {
        if (data.deletedAt() != null) {
            return MarketplaceListingStatus.DELETED;
        }

        if (data.hiddenAt() != null) {
            return MarketplaceListingStatus.HIDDEN;
        }

        if ("published".equalsIgnoreCase(data.status())) {
            return MarketplaceListingStatus.PUBLISHED;
        }

        return MarketplaceListingStatus.UNKNOWN;
    }

    private ProductStatus mapProductStatus(
            MarketplaceListingStatus listingStatus
    ) {
        return switch (listingStatus) {
            case PUBLISHED -> ProductStatus.LISTED;
            case HIDDEN -> ProductStatus.READY;
            case DELETED -> ProductStatus.ARCHIVED;
            case UNKNOWN -> ProductStatus.DRAFT;
        };
    }

    private String findLeafCategory(
            YagaImportedProductData data
    ) {
        if (data.categoryPath().isEmpty()) {
            return null;
        }

        return data.categoryPath()
                .getLast()
                .title();
    }
}