package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.*;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.*;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublicProductUrlValidator;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "yaga.publishing.enabled", havingValue = "true")
public class YagaManualListingReconciliationService {
    private final YagaAccountService accounts;
    private final ProductRepository products;
    private final ProductImageRepository productImages;
    private final MarketplaceListingRepository listings;
    private final YagaRefreshJobRepository jobs;
    private final YagaPageDataClient pages;
    private final YagaOrderedImageVerifier images;
    private final YagaPublicationReconciliationService publicationReconciliation;
    private final Clock clock;

    public YagaManualListingReconciliationService(YagaAccountService accounts, ProductRepository products,
            ProductImageRepository productImages, MarketplaceListingRepository listings,
            YagaRefreshJobRepository jobs, YagaPageDataClient pages, YagaOrderedImageVerifier images,
            YagaPublicationReconciliationService publicationReconciliation, Clock clock) {
        this.accounts = accounts;
        this.products = products;
        this.productImages = productImages;
        this.listings = listings;
        this.jobs = jobs;
        this.pages = pages;
        this.images = images;
        this.publicationReconciliation = publicationReconciliation;
        this.clock = clock;
    }

    @Transactional
    public YagaManualListingReconcileResponse reconcile(Long accountId, Long productId,
            YagaManualListingReconcileRequest request) {
        require(request != null && request.sourceListingId() != null &&
                request.manualExternalListingId() != null && request.publicUrl() != null,
                "Manual listing identity is required");
        var account = accounts.lockForRefreshPlanning(accountId);
        Product product = products.findById(productId).orElseThrow(() -> invalid("Product is missing"));
        MarketplaceListing source = listings.findByIdWithImagesForUpdate(request.sourceListingId())
                .orElseThrow(() -> invalid("Source listing is missing"));
        require(source.getMarketplace() == Marketplace.YAGA && source.getYagaAccount() != null &&
                accountId.equals(source.getYagaAccount().getId()) && productId.equals(source.getProduct().getId()) &&
                account.getShopSlug().equals(source.getShopSlug()), "Source listing ownership differs");
        require(YagaPublicProductUrlValidator.isExpectedPublicProductUrl(source.getExternalUrl(),
                account.getShopSlug(), source.getProductSlug()), "Source listing URL is invalid");
        require(!jobs.existsByProductIdAndStatusIn(productId, List.of(YagaRefreshJobStatus.SELECTED,
                YagaRefreshJobStatus.PUBLISHING, YagaRefreshJobStatus.NEW_LISTING_CONFIRMED,
                YagaRefreshJobStatus.HIDING_OLD, YagaRefreshJobStatus.RESULT_UNKNOWN)),
                "Another refresh job is unresolved");

        String manualSlug = manualSlug(request.publicUrl(), account.getShopSlug());
        require(!source.getExternalListingId().equals(request.manualExternalListingId().toString()),
                "Manual listing equals source listing");
        List<MarketplaceListing> local = listings.findAllByProductIdAndYagaAccountIdAndMarketplaceOrderByIdAsc(
                productId, accountId, Marketplace.YAGA);
        List<YagaRefreshJob> history = jobs.findAllByRunYagaAccountIdAndProductId(accountId, productId);
        List<MarketplaceListing> failedReplacements = failedReplacements(source, local, history);
        MarketplaceListing existingManual = listings.findByYagaAccountIdAndMarketplaceAndExternalListingId(
                accountId, Marketplace.YAGA, request.manualExternalListingId().toString()).orElse(null);
        if (existingManual != null) {
            require(productId.equals(existingManual.getProduct().getId()) &&
                    request.publicUrl().equals(existingManual.getExternalUrl()) && existingManual.isCurrent() &&
                    existingManual.getStatus() == MarketplaceListingStatus.PUBLISHED,
                    "Manual listing is already linked in a different state");
        }
        require(local.stream().filter(MarketplaceListing::isCurrent).allMatch(listing ->
                listing.getId().equals(existingManual == null ? source.getId() : existingManual.getId())),
                "A different current listing exists");
        require(existingManual != null || (source.isCurrent() && source.getStatus() == MarketplaceListingStatus.PUBLISHED),
                "Source listing is not the current publication");
        require(local.stream().noneMatch(listing -> listing.getStatus() == MarketplaceListingStatus.PUBLISHED &&
                !listing.getId().equals(source.getId()) &&
                (existingManual == null || !listing.getId().equals(existingManual.getId())) &&
                failedReplacements.stream().noneMatch(replacement -> replacement.getId().equals(listing.getId()))),
                "An unrelated published listing exists");

        Map<Long, YagaImportedProductData> deletedRemote = new HashMap<>();
        List<MarketplaceListing> toVerify = new ArrayList<>();
        toVerify.add(source);
        toVerify.addAll(failedReplacements);
        for (MarketplaceListing listing : toVerify) {
            YagaImportedProductData data = readRemote(listing.getExternalUrl());
            requireRemoteDeleted(account.getShopSlug(), listing, data);
            deletedRemote.put(listing.getId(), data);
        }
        YagaImportedProductData manual = readRemote(request.publicUrl());
        require(Objects.equals(manual.externalId(), request.manualExternalListingId()) &&
                account.getShopSlug().equals(manual.shopSlug()) && manualSlug.equals(manual.productSlug()) &&
                "published".equalsIgnoreCase(manual.status()) && manual.hiddenAt() == null &&
                manual.deletedAt() == null, "Manual listing is not the expected published listing");
        requireManualProductMatch(product, source, manual);
        List<MarketplaceListingImage> linkedSourceImages = linkedSourceImages(product, source,
                deletedRemote.get(source.getId()));
        images.verify(source.getProductSlug(), deletedRemote.get(source.getId()).images(),
                manualSlug, manual.images());

        if (existingManual != null) {
            requireManualImages(existingManual, linkedSourceImages, manual);
            require(toVerify.stream().allMatch(listing -> listing.getStatus() == MarketplaceListingStatus.DELETED &&
                    !listing.isCurrent()), "Local deleted listings changed after reconciliation");
            return response(accountId, productId, existingManual, toVerify, false);
        }

        var attached = publicationReconciliation.manuallyAttachExistingPublication(source.getId(),
                new YagaListingPublicationReconcileRequest(request.publicUrl()));
        require(productId.equals(attached.productId()) &&
                request.manualExternalListingId().toString().equals(attached.externalListingId()),
                "Manual listing attachment identity differs");
        MarketplaceListing manualLocal = listings.findByIdWithImagesForUpdate(attached.newListingId())
                .orElseThrow(() -> invalid("Manual listing attachment is missing"));
        require(!manualLocal.isCurrent() && accountId.equals(manualLocal.getYagaAccount().getId()),
                "Manual listing attachment state differs");
        requireManualImages(manualLocal, linkedSourceImages, manual);

        Instant now = clock.instant();
        for (MarketplaceListing listing : toVerify) {
            YagaImportedProductData data = deletedRemote.get(listing.getId());
            listing.setStatus(MarketplaceListingStatus.DELETED);
            listing.setExternalStatus(data.status());
            listing.setCurrent(false);
            listing.setDeletedAt(data.deletedAt());
            listing.setLastSyncedAt(now);
            listings.save(listing);
        }
        listings.flush();
        manualLocal.setCurrent(true);
        manualLocal.setLastSyncedAt(now);
        listings.saveAndFlush(manualLocal);
        return response(accountId, productId, manualLocal, toVerify, true);
    }

    private List<MarketplaceListing> failedReplacements(MarketplaceListing source,
            List<MarketplaceListing> local, List<YagaRefreshJob> history) {
        List<MarketplaceListing> replacements = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (YagaRefreshJob job : history) {
            if (job.getStatus() != YagaRefreshJobStatus.FAILED ||
                    !"PUBLISHED".equals(job.getPublicationStatus()) ||
                    !source.getId().equals(job.getOldListing().getId())) {
                continue;
            }
            require(job.getNewListing() != null,
                    "Confirmed replacement listing is missing from a failed job");
            require(job.getRun().getStatus() == YagaRefreshRunStatus.COMPLETED_WITH_ERRORS ||
                    job.getRun().getStatus() == YagaRefreshRunStatus.FAILED,
                    "Failed replacement run is not terminal");
            Long replacementId = job.getNewListing().getId();
            MarketplaceListing replacement = local.stream().filter(item -> replacementId.equals(item.getId()))
                    .findFirst().orElseThrow(() -> invalid("Failed replacement listing is missing"));
            require(!replacement.isCurrent() && !replacement.getId().equals(source.getId()) &&
                    Objects.equals(job.getNewExternalListingId(), replacement.getExternalListingId()) &&
                    Objects.equals(job.getNewProductUrl(), replacement.getExternalUrl()),
                    "Failed replacement identity differs");
            if (seen.add(replacementId)) replacements.add(replacement);
        }
        require(!replacements.isEmpty(), "No failed published replacement exists");
        replacements.sort(Comparator.comparing(MarketplaceListing::getId));
        return replacements;
    }

    private void requireRemoteDeleted(String shopSlug, MarketplaceListing listing, YagaImportedProductData data) {
        require(YagaPublicProductUrlValidator.isExpectedPublicProductUrl(listing.getExternalUrl(),
                shopSlug, listing.getProductSlug()) && data != null &&
                Objects.equals(data.externalId(), externalId(listing)) &&
                shopSlug.equals(data.shopSlug()) && Objects.equals(listing.getProductSlug(), data.productSlug()) &&
                "deleted".equalsIgnoreCase(data.status()) && data.deletedAt() != null,
                "Recorded Yaga listing is not verified deleted");
    }

    private void requireManualProductMatch(Product product, MarketplaceListing source,
            YagaImportedProductData manual) {
        String title = manual.title();
        if (title == null && manual.description() != null) {
            title = manual.description().lines().map(String::trim).filter(line -> !line.isBlank())
                    .findFirst().orElse(null);
        }
        require(title != null && title.trim().equals(product.getTitle().trim()) &&
                manual.description() != null && product.getDescription() != null &&
                manual.description().trim().equals(product.getDescription().trim()),
                "Manual listing title or description differs");
        List<MarketplaceListingCategory> categories = source.getCategories().stream()
                .sorted(Comparator.comparingInt(MarketplaceListingCategory::getCategoryLevel)).toList();
        require(manual.categoryPath() != null && categories.size() == manual.categoryPath().size(),
                "Manual listing category path differs");
        for (int index = 0; index < categories.size(); index++) {
            var expected = categories.get(index);
            var actual = manual.categoryPath().get(index);
            require(Objects.equals(expected.getExternalCategoryId(), actual.id()) &&
                    Objects.equals(expected.getTitle(), actual.title()), "Manual listing category path differs");
        }
    }

    private List<MarketplaceListingImage> linkedSourceImages(Product product, MarketplaceListing source,
            YagaImportedProductData sourceRemote) {
        List<MarketplaceListingImage> linked = source.getImages().stream()
                .sorted(Comparator.comparingInt(MarketplaceListingImage::getDisplayOrder)).toList();
        List<ProductImage> originals = productImages.findAllByProductIdOrderByDisplayOrderAsc(product.getId());
        require(sourceRemote.images() != null && !linked.isEmpty() &&
                linked.size() == sourceRemote.images().size() && linked.size() == originals.size(),
                "Source image linkage is incomplete");
        Set<Long> linkedIds = new HashSet<>();
        for (int index = 0; index < linked.size(); index++) {
            var local = linked.get(index);
            var remote = sourceRemote.images().get(index);
            require(local.getDisplayOrder() == index && local.getProductImage() != null &&
                    local.getProductImage().getId().equals(originals.get(index).getId()) &&
                    linkedIds.add(local.getProductImage().getId()) &&
                    Objects.equals(local.getExternalImageId(), remote.id()) &&
                    Objects.equals(local.getSourceUrl(), remote.originalUrl()),
                    "Source image identity or order differs");
        }
        return linked;
    }

    private void requireManualImages(MarketplaceListing listing, List<MarketplaceListingImage> linkedSource,
            YagaImportedProductData manual) {
        List<MarketplaceListingImage> attached = listing.getImages().stream()
                .sorted(Comparator.comparingInt(MarketplaceListingImage::getDisplayOrder)).toList();
        require(attached.size() == manual.images().size(), "Manual image count changed during attachment");
        for (int index = 0; index < attached.size(); index++) {
            var local = attached.get(index);
            var remote = manual.images().get(index);
            require(local.getDisplayOrder() == index &&
                    Objects.equals(local.getExternalImageId(), remote.id()) &&
                    Objects.equals(local.getSourceUrl(), remote.originalUrl()) &&
                    local.getProductImage() != null &&
                    local.getProductImage().getId().equals(linkedSource.get(index).getProductImage().getId()),
                    "Manual image attachment changed identity or order");
        }
    }

    private String manualSlug(String url, String shopSlug) {
        if (url != null) {
            String[] path = url.split("/");
            if (path.length == 6 && YagaPublicProductUrlValidator.isExpectedPublicProductUrl(
                    url, shopSlug, path[5])) return path[5];
        }
        throw invalid("Manual listing URL is invalid");
    }

    private Long externalId(MarketplaceListing listing) {
        try {
            return Long.valueOf(listing.getExternalListingId());
        } catch (NumberFormatException exception) {
            throw invalid("Recorded listing external ID is invalid");
        }
    }

    private YagaImportedProductData readRemote(String url) {
        try {
            return pages.getProduct(url);
        } catch (RuntimeException exception) {
            throw invalid("Yaga listing state could not be read");
        }
    }

    private YagaManualListingReconcileResponse response(Long accountId, Long productId,
            MarketplaceListing manual, List<MarketplaceListing> inactive, boolean reconciled) {
        return new YagaManualListingReconcileResponse(accountId, productId, manual.getId(),
                manual.getExternalListingId(), inactive.stream().map(MarketplaceListing::getId).toList(), reconciled);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static YagaRefreshInvalidStateException invalid(String message) {
        return new YagaRefreshInvalidStateException(message);
    }
}
