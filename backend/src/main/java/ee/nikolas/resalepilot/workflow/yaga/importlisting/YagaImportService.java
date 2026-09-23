package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class YagaImportService {

    private final YagaPageDataClient pageDataClient;
    private final ProductRepository productRepository;
    private final MarketplaceListingRepository listingRepository;
    private final YagaAccountService accountService;
    private final YagaProductTitleResolver titleResolver;
    private final TransactionTemplate transactionTemplate;

    public YagaImportService(
            YagaPageDataClient pageDataClient,
            ProductRepository productRepository,
            MarketplaceListingRepository listingRepository,
            YagaAccountService accountService,
            YagaProductTitleResolver titleResolver,
            PlatformTransactionManager transactionManager
    ) {
        this.pageDataClient = pageDataClient;
        this.productRepository = productRepository;
        this.listingRepository = listingRepository;
        this.accountService = accountService;
        this.titleResolver = titleResolver;
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

    public YagaImportResponse importFetchedProduct(
            String sku,
            String title,
            ProductCondition conditionOverride,
            YagaImportedProductData data
    ) {
        YagaImportResponse response =
                transactionTemplate.execute(status ->
                        persistImport(
                                sku,
                                title,
                                conditionOverride,
                                data
                        )
                );

        if (response == null) {
            throw new IllegalStateException(
                    "Yaga import transaction returned no result"
            );
        }

        return response;
    }

    public YagaImportUpsertResult importOrUpdateFetchedProduct(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            ProductCondition conditionOverride,
            YagaImportedProductData data
    ) {
        YagaImportUpsertResult response =
                transactionTemplate.execute(status ->
                        persistUpsert(
                                account,
                                sku(account, data),
                                resolvedTitle(data),
                                conditionOverride,
                                data
                        )
                );

        if (response == null) {
            throw new IllegalStateException(
                    "Yaga import upsert transaction returned no result"
            );
        }

        return response;
    }

    private YagaImportResponse persistImport(
            YagaImportRequest request,
            YagaImportedProductData data
    ) {
        return persistImport(
                request.sku(),
                resolvedTitle(data),
                request.conditionOverride(),
                data
        );
    }

    private YagaImportResponse persistImport(
            String sku,
            String title,
            ProductCondition conditionOverride,
            YagaImportedProductData data
    ) {
        return persistImport(
                accountService.requireByShopSlug(data.shopSlug()),
                sku,
                title,
                conditionOverride,
                data
        );
    }

    private YagaImportResponse persistImport(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            String sku,
            String title,
            ProductCondition conditionOverride,
            YagaImportedProductData data
    ) {
        String externalListingId =
                data.externalId().toString();

        if (productRepository.existsBySku(sku)) {
            throw new YagaImportConflictException(
                    "Product SKU already exists: " +
                            sku
            );
        }

        if (listingRepository
                .existsByYagaAccountIdAndMarketplaceAndExternalListingId(
                        account.getId(),
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
                sku,
                validateResolvedTitle(title)
        );

        product.setDescription(data.description());
        product.setAskingPrice(data.price());
        product.setCondition(
                resolveProductCondition(
                        data,
                        conditionOverride
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
        listing.setYagaAccount(account);
        listing.setProductSlug(data.productSlug());
        syncListingFields(listing, data, listingStatus);

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

    private YagaImportUpsertResult persistUpsert(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            String sku,
            String title,
            ProductCondition conditionOverride,
            YagaImportedProductData data
    ) {
        validateAccount(account, data);
        Optional<MarketplaceListing> existing =
                findExisting(account, data);

        if (existing.isEmpty()) {
            YagaImportResponse created = persistImport(
                    account,
                    sku,
                    title,
                    conditionOverride,
                    data
            );
            return new YagaImportUpsertResult(created, true);
        }

        MarketplaceListing listing = existing.get();
        Product product = listing.getProduct();
        MarketplaceListingStatus listingStatus = mapListingStatus(data);

        product.setTitle(validateResolvedTitle(title));
        product.setDescription(data.description());
        product.setAskingPrice(data.price());
        product.setCondition(
                resolveProductCondition(data, conditionOverride)
        );
        product.setCategory(findLeafCategory(data));
        product.setStatus(mapProductStatus(listingStatus));

        listing.setExternalListingId(data.externalId().toString());
        listing.setExternalUrl(canonicalUrl(data));
        listing.setShopSlug(data.shopSlug());
        listing.setYagaAccount(account);
        listing.setProductSlug(data.productSlug());
        syncListingFields(listing, data, listingStatus);

        MarketplaceListing savedListing =
                listingRepository.saveAndFlush(listing);

        List<String> categoryPath =
                savedListing.getCategories()
                        .stream()
                        .map(MarketplaceListingCategory::getTitle)
                        .toList();

        return new YagaImportUpsertResult(
                new YagaImportResponse(
                        ProductResponse.from(product),
                        savedListing.getId(),
                        savedListing.getExternalListingId(),
                        savedListing.getExternalUrl(),
                        savedListing.getStatus(),
                        categoryPath,
                        savedListing.getImages().size()
                ),
                false
        );
    }

    private Optional<MarketplaceListing> findExisting(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            YagaImportedProductData data
    ) {
        Optional<MarketplaceListing> byExternalId =
                listingRepository
                        .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                                account.getId(),
                                Marketplace.YAGA,
                                data.externalId().toString()
                        );
        if (byExternalId.isPresent()) {
            return byExternalId;
        }
        return listingRepository
                .findByYagaAccountIdAndMarketplaceAndShopSlugAndProductSlug(
                        account.getId(),
                        Marketplace.YAGA,
                        data.shopSlug(),
                        data.productSlug()
                );
    }

    private void syncListingFields(
            MarketplaceListing listing,
            YagaImportedProductData data,
            MarketplaceListingStatus listingStatus
    ) {
        listing.setStatus(listingStatus);
        listing.setExternalStatus(data.status());
        listing.setCurrent(true);
        listing.setExternalCreatedAt(data.createdAt());
        listing.setExternalUpdatedAt(data.updatedAt());
        listing.setHiddenAt(data.hiddenAt());
        listing.setDeletedAt(data.deletedAt());
        listing.setLastSyncedAt(Instant.now());

        if (data.condition() == null) {
            listing.setExternalConditionId(null);
            listing.setExternalConditionName(null);
        } else {
            listing.setExternalConditionId(data.condition().id());
            listing.setExternalConditionName(data.condition().name());
        }

        syncCategories(listing, data);
        syncImages(listing, data);
    }

    private void syncCategories(
            MarketplaceListing listing,
            YagaImportedProductData data
    ) {
        Map<Integer, MarketplaceListingCategory> existingByLevel =
                new LinkedHashMap<>();
        for (MarketplaceListingCategory category : listing.getCategories()) {
            existingByLevel.put(category.getCategoryLevel(), category);
        }
        listing.getCategories().removeIf(category ->
                category.getCategoryLevel() >= data.categoryPath().size()
        );
        for (int level = 0; level < data.categoryPath().size(); level++) {
            YagaImportedProductData.Category category =
                    data.categoryPath().get(level);
            MarketplaceListingCategory listingCategory =
                    existingByLevel.get(level);
            if (listingCategory == null) {
                listingCategory = new MarketplaceListingCategory(
                        level,
                        category.id(),
                        category.parentId(),
                        category.title()
                );
                listing.addCategory(listingCategory);
            } else {
                listingCategory.setExternalCategoryId(category.id());
                listingCategory.setParentExternalCategoryId(
                        category.parentId()
                );
                listingCategory.setTitle(category.title());
            }
        }
    }

    private void syncImages(
            MarketplaceListing listing,
            YagaImportedProductData data
    ) {
        Map<String, MarketplaceListingImage> existingByExternalId =
                new LinkedHashMap<>();
        for (MarketplaceListingImage image : listing.getImages()) {
            existingByExternalId.put(image.getExternalImageId(), image);
        }
        List<String> incomingImageIds =
                data.images()
                        .stream()
                        .map(YagaImportedProductData.Image::id)
                        .toList();
        listing.getImages().removeIf(image ->
                !incomingImageIds.contains(image.getExternalImageId())
        );
        for (int order = 0; order < data.images().size(); order++) {
            YagaImportedProductData.Image image = data.images().get(order);
            MarketplaceListingImage listingImage =
                    existingByExternalId.get(image.id());
            if (listingImage == null) {
                listingImage = new MarketplaceListingImage(
                        image.id(),
                        image.originalUrl(),
                        image.fileName(),
                        order
                );
                listing.addImage(listingImage);
            }
            listingImage.setSourceUrl(image.originalUrl());
            listingImage.setFileName(image.fileName());
            listingImage.setDisplayOrder(order);
        }
        listing.getImages().sort(
                Comparator.comparingInt(
                        MarketplaceListingImage::getDisplayOrder
                )
        );
    }

    private void validateAccount(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            YagaImportedProductData data
    ) {
        if (account == null || account.getId() == null) {
            throw new IllegalArgumentException("Yaga account is required");
        }
        if (!account.isEnabled()) {
            throw new IllegalArgumentException("Yaga account is disabled");
        }
        if (!account.getShopSlug().equals(data.shopSlug())) {
            throw new IllegalArgumentException(
                    "Imported Yaga listing belongs to another shop"
            );
        }
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
            case SOLD -> ProductStatus.SOLD;
            case HIDDEN -> ProductStatus.READY;
            case DELETED -> ProductStatus.ARCHIVED;
            case UNAVAILABLE, UNKNOWN -> ProductStatus.DRAFT;
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

    private String resolvedTitle(YagaImportedProductData data) {
        return titleResolver.resolve(data)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Yaga product title cannot be resolved"
                ));
    }

    private String validateResolvedTitle(String title) {
        return titleResolver.validate(title)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product title is required"
                ));
    }

    private String sku(
            ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount account,
            YagaImportedProductData data
    ) {
        if (account.getId() == null || account.getId() ==
                ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountAuthStateResolver
                        .LEGACY_DEFAULT_ACCOUNT_ID) {
            return "YAGA-" + data.externalId();
        }
        return "YAGA-A" + account.getId() + "-" + data.externalId();
    }

    private String canonicalUrl(YagaImportedProductData data) {
        return String.format(
                "https://www.yaga.ee/%s/toode/%s",
                data.shopSlug(),
                data.productSlug()
        );
    }

    public record YagaImportUpsertResult(
            YagaImportResponse response,
            boolean created
    ) {
    }
}
