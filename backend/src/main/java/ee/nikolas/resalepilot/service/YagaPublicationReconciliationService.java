package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaListingPublicationReconcileRequest;
import ee.nikolas.resalepilot.dto.YagaListingPublicationReconcileResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.entity.*;
import ee.nikolas.resalepilot.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.exception.YagaPublicationReconciliationConflictException;
import ee.nikolas.resalepilot.exception.YagaPublishingDataInvalidException;
import ee.nikolas.resalepilot.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.repository.ProductImageRepository;
import ee.nikolas.resalepilot.yaga.YagaImportedProductData;
import ee.nikolas.resalepilot.yaga.YagaPageDataClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class YagaPublicationReconciliationService {

    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final YagaPageDataClient pageDataClient;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;
    private final YagaPublishedUrlResolver publishedUrlResolver =
            new YagaPublishedUrlResolver();

    public YagaPublicationReconciliationService(
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            YagaPageDataClient pageDataClient,
            PlatformTransactionManager transactionManager
    ) {
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.pageDataClient = pageDataClient;

        this.readOnlyTransaction =
                new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction =
                new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED
        );
    }

    public YagaListingPublicationReconcileResponse reconcile(
            Long oldListingId,
            YagaListingPublicationReconcileRequest request
    ) {
        if (request == null || isBlank(request.publicUrl())) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga public URL is required"
            );
        }

        YagaPublishedUrl resolved = resolvePublicUrl(request.publicUrl());
        OldListingSnapshot oldListing =
                loadOldListingSnapshot(oldListingId);

        if (!resolved.shopSlug().equals(oldListing.shopSlug())) {
            throw conflict(
                    "Published Yaga shop slug does not match source listing",
                    "shopSlug",
                    oldListing.shopSlug(),
                    resolved.shopSlug()
            );
        }

        YagaListingPublicationReconcileResponse existingResponse =
                findExistingPublishedListing(
                        oldListing,
                        resolved
                );
        if (existingResponse != null) {
            return existingResponse;
        }

        YagaImportedProductData data =
                pageDataClient.getProduct(resolved.publicUrl());
        validatePublishedData(oldListing, resolved, data);

        return writeTransaction.execute(status ->
                createOrFindPublishedListing(
                        oldListingId,
                        resolved,
                        data
                )
        );
    }

    private YagaPublishedUrl resolvePublicUrl(String publicUrl) {
        try {
            YagaPublishedUrl resolved =
                    publishedUrlResolver.resolve(publicUrl, null);
            if (!resolved.publicProductUrl()) {
                throw new YagaPublishingDataInvalidException(
                        "Yaga URL must be a public product URL"
                );
            }
            return resolved;
        } catch (YagaPublishingDataInvalidException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new YagaPublishingDataInvalidException(
                    "Invalid Yaga public product URL"
            );
        }
    }

    private OldListingSnapshot loadOldListingSnapshot(Long oldListingId) {
        OldListingSnapshot snapshot =
                readOnlyTransaction.execute(status -> {
                    MarketplaceListing listingWithCategories =
                            listingRepository
                                    .findByIdWithCategories(oldListingId)
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    oldListingId
                                            )
                                    );

                    MarketplaceListing listingWithImages =
                            listingRepository
                                    .findByIdWithImagesAndProductImages(
                                            oldListingId
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    oldListingId
                                            )
                                    );

                    Product product = listingWithImages.getProduct();
                    List<String> categoryPath =
                            listingWithCategories.getCategories()
                                    .stream()
                                    .sorted(Comparator.comparingInt(
                                            MarketplaceListingCategory::getCategoryLevel
                                    ))
                                    .map(MarketplaceListingCategory::getTitle)
                                    .toList();
                    List<Long> productImageIds =
                            listingWithImages.getImages()
                                    .stream()
                                    .sorted(Comparator.comparingInt(
                                            MarketplaceListingImage::getDisplayOrder
                                    ))
                                    .map(MarketplaceListingImage::getProductImage)
                                    .map(productImage -> productImage == null
                                            ? null
                                            : productImage.getId())
                                    .toList();

                    return new OldListingSnapshot(
                            oldListingId,
                            product.getId(),
                            listingWithImages.getShopSlug(),
                            product.getDescription(),
                            product.getAskingPrice(),
                            product.getCondition(),
                            categoryPath,
                            productImageIds,
                            listingWithImages.getStatus(),
                            listingWithImages.getHiddenAt(),
                            listingWithImages.getDeletedAt()
                    );
                });

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Yaga publication reconciliation transaction returned no result"
            );
        }

        if (isBlank(snapshot.shopSlug())) {
            throw new YagaPublishingDataInvalidException(
                    "Source Yaga listing shop slug is required"
            );
        }
        if (snapshot.productImageIds().stream()
                .anyMatch(id -> id == null)) {
            throw new YagaPublishingDataInvalidException(
                    "Source Yaga listing images must be linked to ProductImage"
            );
        }

        return snapshot;
    }

    private YagaListingPublicationReconcileResponse
    findExistingPublishedListing(
            OldListingSnapshot oldListing,
            YagaPublishedUrl resolved
    ) {
        return readOnlyTransaction.execute(status ->
                listingRepository
                        .findByMarketplaceAndShopSlugAndProductSlug(
                                Marketplace.YAGA,
                                resolved.shopSlug(),
                                resolved.productSlug()
                        )
                        .map(existing -> {
                            Long existingProductId =
                                    existing.getProduct().getId();

                            if (!oldListing.productId()
                                    .equals(existingProductId)) {
                                throw conflict(
                                        "Published Yaga listing belongs to a different product",
                                        "productId",
                                        oldListing.productId().toString(),
                                        existingProductId.toString()
                                );
                            }

                            return response(
                                    oldListing.oldListingId(),
                                    existing,
                                    existing.getImages().size()
                            );
                        })
                        .orElse(null)
        );
    }

    private void validatePublishedData(
            OldListingSnapshot oldListing,
            YagaPublishedUrl resolved,
            YagaImportedProductData data
    ) {
        Map<String, String> mismatches = new LinkedHashMap<>();

        if (!resolved.shopSlug().equals(data.shopSlug())) {
            mismatches.put("shopSlug", "Published shop slug differs");
        }

        if (!resolved.productSlug().equals(data.productSlug())) {
            mismatches.put("productSlug", "Published product slug differs");
        }

        if (data.description() == null ||
                !data.description().trim()
                        .equals(oldListing.description().trim())) {
            mismatches.put("description", "Published description differs");
        }

        if (!samePrice(data.price(), oldListing.askingPrice())) {
            mismatches.put("price", "Published price differs");
        }

        ProductCondition condition =
                mapPublishedCondition(data.condition());
        if (condition != oldListing.condition()) {
            mismatches.put("condition", "Published condition differs");
        }

        List<String> categoryPath = data.categoryPath()
                .stream()
                .map(YagaImportedProductData.Category::title)
                .toList();
        if (!categoryPath.equals(oldListing.categoryPath())) {
            mismatches.put("categoryPath", "Published category path differs");
        }

        if (data.images().size() != oldListing.productImageIds().size()) {
            mismatches.put("imageCount", "Published image count differs");
        }

        if (!mismatches.isEmpty()) {
            throw new YagaPublicationReconciliationConflictException(
                    "Published Yaga listing does not match source product",
                    mismatches
            );
        }
    }

    private YagaListingPublicationReconcileResponse
    createOrFindPublishedListing(
            Long oldListingId,
            YagaPublishedUrl resolved,
            YagaImportedProductData data
    ) {
        String externalListingId = data.externalId().toString();

        MarketplaceListing existing =
                listingRepository.findByMarketplaceAndExternalListingId(
                                Marketplace.YAGA,
                                externalListingId
                        )
                        .or(() ->
                                listingRepository
                                        .findByMarketplaceAndShopSlugAndProductSlug(
                                                Marketplace.YAGA,
                                                resolved.shopSlug(),
                                                resolved.productSlug()
                                        )
                        )
                        .orElse(null);

        if (existing != null) {
            return response(
                    oldListingId,
                    existing,
                    data.images().size()
            );
        }

        MarketplaceListing oldListing =
                listingRepository.findByIdWithImagesAndProductImages(
                                oldListingId
                        )
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        oldListingId
                                )
                        );
        Product product = oldListing.getProduct();
        List<ProductImage> productImages =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                product.getId()
                        );

        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        externalListingId,
                        resolved.publicUrl()
                );
        listing.setShopSlug(resolved.shopSlug());
        listing.setProductSlug(resolved.productSlug());
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setExternalStatus(data.status());
        if (data.condition() != null) {
            listing.setExternalConditionId(data.condition().id());
            listing.setExternalConditionName(data.condition().name());
        }
        listing.setCurrent(false);
        listing.setExternalCreatedAt(data.createdAt());
        listing.setExternalUpdatedAt(data.updatedAt());
        listing.setLastSyncedAt(Instant.now());

        for (int index = 0; index < data.categoryPath().size(); index++) {
            YagaImportedProductData.Category category =
                    data.categoryPath().get(index);
            listing.addCategory(
                    new MarketplaceListingCategory(
                            index,
                            category.id(),
                            category.parentId(),
                            category.title()
                    )
            );
        }

        for (int index = 0; index < data.images().size(); index++) {
            YagaImportedProductData.Image image =
                    data.images().get(index);
            MarketplaceListingImage listingImage =
                    new MarketplaceListingImage(
                            image.id(),
                            image.originalUrl(),
                            image.fileName(),
                            index
                    );
            if (index < productImages.size()) {
                listingImage.setProductImage(productImages.get(index));
            }
            listing.addImage(listingImage);
        }

        MarketplaceListing saved =
                listingRepository.saveAndFlush(listing);
        return response(oldListingId, saved, data.images().size());
    }

    private YagaListingPublicationReconcileResponse response(
            Long oldListingId,
            MarketplaceListing listing,
            int imageCount
    ) {
        return new YagaListingPublicationReconcileResponse(
                oldListingId,
                listing.getId(),
                listing.getProduct().getId(),
                true,
                listing.getExternalListingId(),
                listing.getExternalUrl(),
                listing.getShopSlug(),
                listing.getProductSlug(),
                imageCount,
                YagaPublicationStatus.PUBLISHED
        );
    }

    private boolean samePrice(
            BigDecimal actual,
            BigDecimal expected
    ) {
        return actual != null &&
                expected != null &&
                actual.compareTo(expected) == 0;
    }

    private ProductCondition mapPublishedCondition(
            YagaImportedProductData.Condition condition
    ) {
        if (condition == null || condition.id() == null) {
            throw new YagaPublishingFormException(
                    "Published Yaga condition is missing"
            );
        }

        return switch (condition.id().intValue()) {
            case 1 -> ProductCondition.NEW_WITHOUT_TAGS;
            case 2 -> ProductCondition.VERY_GOOD;
            case 3 -> ProductCondition.GOOD;
            case 4 -> ProductCondition.SATISFACTORY;
            default -> throw new YagaPublishingFormException(
                    "Published Yaga condition is unsupported"
            );
        };
    }

    private YagaPublicationReconciliationConflictException conflict(
            String message,
            String field,
            String expected,
            String actual
    ) {
        return new YagaPublicationReconciliationConflictException(
                message,
                Map.of(
                        field,
                        "expected=%s, actual=%s".formatted(
                                expected,
                                actual
                        )
                )
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record OldListingSnapshot(
            Long oldListingId,
            Long productId,
            String shopSlug,
            String description,
            BigDecimal askingPrice,
            ProductCondition condition,
            List<String> categoryPath,
            List<Long> productImageIds,
            MarketplaceListingStatus status,
            Instant hiddenAt,
            Instant deletedAt
    ) {
    }
}
