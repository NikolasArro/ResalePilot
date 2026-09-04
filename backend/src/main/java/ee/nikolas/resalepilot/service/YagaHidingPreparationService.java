package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.config.YagaHidingProperties;
import ee.nikolas.resalepilot.dto.YagaHideReconcileResponse;
import ee.nikolas.resalepilot.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.entity.*;
import ee.nikolas.resalepilot.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.exception.YagaHidingPreconditionException;
import ee.nikolas.resalepilot.exception.YagaPreparationAlreadyRunningException;
import ee.nikolas.resalepilot.exception.YagaPublicationReconciliationConflictException;
import ee.nikolas.resalepilot.exception.YagaPublishingDataInvalidException;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(
        name = "yaga.hiding.enabled",
        havingValue = "true"
)
public class YagaHidingPreparationService {

    private static final String AWAITING_CONFIRMATION =
            "AWAITING_CONFIRMATION";

    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final YagaPageDataClient pageDataClient;
    private final YagaHidingBrowserAutomation browserAutomation;
    private final YagaConfirmationTokenService tokenService;
    private final YagaHidingProperties properties;
    private final Clock clock;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;
    private final AtomicBoolean preparationRunning =
            new AtomicBoolean(false);
    private final Map<UUID, byte[]> tokenHashes =
            new ConcurrentHashMap<>();

    public YagaHidingPreparationService(
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            YagaPageDataClient pageDataClient,
            YagaHidingBrowserAutomation browserAutomation,
            YagaConfirmationTokenService tokenService,
            YagaHidingProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.pageDataClient = pageDataClient;
        this.browserAutomation = browserAutomation;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
        this.readOnlyTransaction =
                new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.readOnlyTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED
        );
        this.writeTransaction =
                new TransactionTemplate(transactionManager);
    }

    public YagaHidePreparationResponse prepareHide(Long oldListingId) {
        if (!preparationRunning.compareAndSet(false, true)) {
            throw new YagaPreparationAlreadyRunningException();
        }

        try {
            YagaHidingDraftSnapshot snapshot =
                    loadAndValidateSnapshot(oldListingId);
            verifyNewListingWithYaga(snapshot);

            YagaHideControlInspection inspection =
                    inspectOnce(snapshot.toDraft());
            validateTarget(snapshot, inspection);

            String token = tokenService.generateToken();
            UUID preparationId = UUID.randomUUID();
            tokenHashes.put(
                    preparationId,
                    tokenService.hashToken(token)
            );

            return new YagaHidePreparationResponse(
                    preparationId,
                    snapshot.oldListingId(),
                    snapshot.newListingId(),
                    snapshot.productId(),
                    snapshot.oldExternalListingId(),
                    snapshot.oldProductSlug(),
                    snapshot.newExternalListingId(),
                    snapshot.newProductSlug(),
                    inspection.currentUrl(),
                    inspection.candidateCount(),
                    inspection.visibleCandidateCount(),
                    inspection.enabledCandidateCount(),
                    inspection.controlText(),
                    inspection.accessibleName(),
                    inspection.tagName(),
                    inspection.typeAttribute(),
                    inspection.readyForConfirmation(),
                    token,
                    Instant.now().plus(properties.getConfirmationTtl()),
                    AWAITING_CONFIRMATION
            );

        } finally {
            preparationRunning.set(false);
        }
    }

    public YagaHidingDraftData loadAndVerifyDraft(Long oldListingId) {
        YagaHidingDraftSnapshot snapshot =
                loadAndValidateSnapshot(oldListingId);
        verifyNewListingWithYaga(snapshot);
        return snapshot.toDraft();
    }

    public void validateReadyInspection(
            YagaHidingDraftData draft,
            YagaHideControlInspection inspection
    ) {
        validateTarget(loadSnapshotForDraft(draft), inspection);
    }

    public void verifyReplacementListing(YagaHidingDraftData draft) {
        verifyNewListingWithYaga(loadSnapshotForDraft(draft));
    }

    public Instant markOldHiddenAndNewCurrent(
            YagaHidingDraftData draft,
            Instant hiddenAt
    ) {
        AtomicReference<Instant> effectiveHiddenAt =
                new AtomicReference<>();

        writeTransaction.executeWithoutResult(status -> {
            MarketplaceListing oldListing =
                    listingRepository.findByIdForUpdate(draft.oldListingId())
                            .orElseThrow(() ->
                                    new MarketplaceListingNotFoundException(
                                            draft.oldListingId()
                                    )
                            );
            MarketplaceListing newListing =
                    listingRepository.findByIdForUpdate(draft.newListingId())
                            .orElseThrow(() ->
                                    new MarketplaceListingNotFoundException(
                                            draft.newListingId()
                                    )
                            );

            Instant confirmedHiddenAt =
                    oldListing.getHiddenAt() != null
                            ? oldListing.getHiddenAt()
                            : hiddenAt == null
                            ? Instant.now(clock)
                            : hiddenAt;
            effectiveHiddenAt.set(confirmedHiddenAt);

            oldListing.setCurrent(false);
            oldListing.setStatus(MarketplaceListingStatus.HIDDEN);
            oldListing.setHiddenAt(confirmedHiddenAt);
            oldListing.setLastSyncedAt(Instant.now(clock));
            listingRepository.saveAndFlush(oldListing);

            newListing.setCurrent(true);
            newListing.setStatus(MarketplaceListingStatus.PUBLISHED);
            newListing.setLastSyncedAt(Instant.now(clock));
            listingRepository.saveAndFlush(newListing);
        });

        return effectiveHiddenAt.get();
    }

    public YagaHideReconcileResponse reconcileHiddenListing(
            Long oldListingId
    ) {
        YagaHidingDraftSnapshot snapshot =
                loadAndValidateRecoverySnapshot(oldListingId);
        verifyNewListingWithYaga(snapshot);

        YagaImportedProductData oldData =
                pageDataClient.getProduct(snapshot.oldExternalUrl());
        if (!oldListingMatches(snapshot, oldData) ||
                !isHiddenYagaStatus(oldData)) {
            throw new YagaPublicationReconciliationConflictException(
                    "Old Yaga listing is not confirmed hidden",
                    Map.of(
                            "oldListingId",
                            snapshot.oldListingId().toString(),
                            "oldStatus",
                            safe(oldData.status()),
                            "oldHiddenAt",
                            oldData.hiddenAt() == null
                                    ? ""
                                    : oldData.hiddenAt().toString()
                    )
            );
        }

        Instant hiddenAt = markOldHiddenAndNewCurrent(
                snapshot.toDraft(),
                oldData.hiddenAt()
        );

        return new YagaHideReconcileResponse(
                snapshot.oldListingId(),
                snapshot.newListingId(),
                true,
                snapshot.oldExternalUrl(),
                hiddenAt,
                YagaHidingStatus.HIDDEN
        );
    }

    public boolean isHiddenYagaStatus(YagaImportedProductData data) {
        if (data == null) {
            return false;
        }
        if (data.hiddenAt() != null) {
            return true;
        }
        String status = data.status();
        return status != null && (
                status.equalsIgnoreCase("hidden") ||
                        status.equalsIgnoreCase("peidetud") ||
                        status.equalsIgnoreCase("not-visible")
        );
    }

    private YagaHideControlInspection inspectOnce(
            YagaHidingDraftData draft
    ) {
        YagaHidingPreparedBrowserSession session =
                browserAutomation.prepareSession(draft);
        try {
            return browserAutomation.inspectHideControl(session);
        } finally {
            browserAutomation.closeSession(session);
        }
    }

    private YagaHidingDraftSnapshot loadSnapshotForDraft(
            YagaHidingDraftData draft
    ) {
        YagaHidingDraftSnapshot snapshot =
                loadAndValidateSnapshot(draft.oldListingId());
        if (!snapshot.newListingId().equals(draft.newListingId())) {
            throw new YagaHidingPreconditionException(
                    "Yaga hiding replacement listing changed"
            );
        }
        return snapshot;
    }

    private YagaHidingDraftSnapshot loadAndValidateRecoverySnapshot(
            Long oldListingId
    ) {
        YagaHidingDraftSnapshot snapshot =
                readOnlyTransaction.execute(status -> {
                    MarketplaceListing oldListing =
                            listingRepository
                                    .findByIdWithImagesAndProductImages(
                                            oldListingId
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    oldListingId
                                            )
                                    );
                    if (oldListing.getStatus() !=
                            MarketplaceListingStatus.PUBLISHED &&
                            oldListing.getStatus() !=
                                    MarketplaceListingStatus.HIDDEN) {
                        throw new YagaHidingPreconditionException(
                                "Old Yaga listing must be published or hidden"
                        );
                    }

                    Product product = oldListing.getProduct();
                    List<MarketplaceListing> publishedListings =
                            listingRepository
                                    .findAllByProductIdAndMarketplaceAndStatus(
                                            product.getId(),
                                            Marketplace.YAGA,
                                            MarketplaceListingStatus.PUBLISHED
                                    );
                    List<MarketplaceListing> newListings =
                            publishedListings.stream()
                                    .filter(listing ->
                                            !listing.getId().equals(
                                                    oldListingId
                                            ))
                                    .toList();

                    if (newListings.size() != 1) {
                        throw new YagaHidingPreconditionException(
                                "Exactly one replacement published Yaga listing is required"
                        );
                    }

                    MarketplaceListing newListing =
                            loadListingWithImages(
                                    newListings.getFirst().getId()
                            );
                    return buildSnapshot(
                            oldListing,
                            newListing,
                            product
                    );
                });

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Yaga hiding recovery snapshot transaction returned no result"
            );
        }
        return snapshot;
    }

    private YagaHidingDraftSnapshot loadAndValidateSnapshot(
            Long oldListingId
    ) {
        YagaHidingDraftSnapshot snapshot =
                readOnlyTransaction.execute(status -> {
                    MarketplaceListing oldListing =
                            listingRepository
                                    .findByIdWithImagesAndProductImages(
                                            oldListingId
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    oldListingId
                                            )
                                    );

                    if (oldListing.getStatus() !=
                            MarketplaceListingStatus.PUBLISHED) {
                        throw new YagaHidingPreconditionException(
                                "Old Yaga listing must be published"
                        );
                    }
                    if (!oldListing.isCurrent()) {
                        throw new YagaHidingPreconditionException(
                                "Old Yaga listing must be current"
                        );
                    }

                    Product product = oldListing.getProduct();
                    List<MarketplaceListing> publishedListings =
                            listingRepository
                                    .findAllByProductIdAndMarketplaceAndStatus(
                                            product.getId(),
                                            Marketplace.YAGA,
                                            MarketplaceListingStatus.PUBLISHED
                                    );
                    List<MarketplaceListing> newListings =
                            publishedListings.stream()
                                    .filter(listing ->
                                            !listing.getId().equals(
                                                    oldListingId
                                            ))
                                    .toList();

                    if (newListings.size() != 1) {
                        throw new YagaHidingPreconditionException(
                                "Exactly one replacement published Yaga listing is required"
                        );
                    }

                    MarketplaceListing newListing =
                            loadListingWithImages(
                                    newListings.getFirst().getId()
                            );

                    if (oldListing.getId().equals(newListing.getId())) {
                        throw new YagaHidingPreconditionException(
                                "Old and new Yaga listings must differ"
                        );
                    }
                    if (sameNonBlank(
                            oldListing.getExternalListingId(),
                            newListing.getExternalListingId()
                    ) || sameNonBlank(
                            oldListing.getProductSlug(),
                            newListing.getProductSlug()
                    )) {
                        throw new YagaHidingPreconditionException(
                                "Replacement Yaga listing must have a different external id and product slug"
                        );
                    }

                    List<ProductImage> productImages =
                            productImageRepository
                                    .findAllByProductIdOrderByDisplayOrderAsc(
                                            product.getId()
                                    );
                    int linkedNewImageCount =
                            linkedImageCount(newListing);
                    if (productImages.isEmpty() ||
                            linkedNewImageCount != productImages.size()) {
                        throw new YagaHidingPreconditionException(
                                "Replacement Yaga listing must have the full image set"
                        );
                    }

                    MarketplaceListing oldWithCategories =
                            listingRepository
                                    .findByIdWithCategories(oldListingId)
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    oldListingId
                                            )
                                    );
                    MarketplaceListing newWithCategories =
                            listingRepository
                                    .findByIdWithCategories(
                                            newListing.getId()
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    newListing.getId()
                                            )
                                    );
                    List<String> oldCategoryPath =
                            categoryPath(oldWithCategories);
                    List<String> newCategoryPath =
                            categoryPath(newWithCategories);

                    if (!oldCategoryPath.equals(newCategoryPath)) {
                        throw new YagaHidingPreconditionException(
                                "Replacement Yaga listing category path must match the old listing"
                        );
                    }

                    return new YagaHidingDraftSnapshot(
                            oldListing.getId(),
                            newListing.getId(),
                            product.getId(),
                            oldListing.getShopSlug(),
                            oldListing.getExternalListingId(),
                            oldListing.getProductSlug(),
                            oldListing.getExternalUrl(),
                            newListing.getExternalListingId(),
                            newListing.getProductSlug(),
                            newListing.getExternalUrl(),
                            product.getDescription(),
                            product.getAskingPrice(),
                            product.getCondition(),
                            oldCategoryPath,
                            newCategoryPath,
                            productImages.size(),
                            newListing.getImages().size()
                    );
                });

        if (snapshot == null) {
            throw new IllegalStateException(
                    "Yaga hiding snapshot transaction returned no result"
            );
        }

        if (isBlank(snapshot.shopSlug()) ||
                isBlank(snapshot.oldProductSlug()) ||
                isBlank(snapshot.newProductSlug()) ||
                isBlank(snapshot.newExternalUrl())) {
            throw new YagaHidingPreconditionException(
                    "Yaga listing identifiers are required for hiding preparation"
            );
        }

        return snapshot;
    }

    private MarketplaceListing loadListingWithImages(Long listingId) {
        return listingRepository
                .findByIdWithImagesAndProductImages(listingId)
                .orElseThrow(() ->
                        new MarketplaceListingNotFoundException(
                                listingId
                        )
                );
    }

    private YagaHidingDraftSnapshot buildSnapshot(
            MarketplaceListing oldListing,
            MarketplaceListing newListing,
            Product product
    ) {
        if (oldListing.getId().equals(newListing.getId())) {
            throw new YagaHidingPreconditionException(
                    "Old and new Yaga listings must differ"
            );
        }
        if (sameNonBlank(
                oldListing.getExternalListingId(),
                newListing.getExternalListingId()
        ) || sameNonBlank(
                oldListing.getProductSlug(),
                newListing.getProductSlug()
        )) {
            throw new YagaHidingPreconditionException(
                    "Replacement Yaga listing must have a different external id and product slug"
            );
        }

        List<ProductImage> productImages =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                product.getId()
                        );
        int linkedNewImageCount = linkedImageCount(newListing);
        if (productImages.isEmpty() ||
                linkedNewImageCount != productImages.size()) {
            throw new YagaHidingPreconditionException(
                    "Replacement Yaga listing must have the full image set"
            );
        }

        MarketplaceListing oldWithCategories =
                listingRepository
                        .findByIdWithCategories(oldListing.getId())
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        oldListing.getId()
                                )
                        );
        MarketplaceListing newWithCategories =
                listingRepository
                        .findByIdWithCategories(newListing.getId())
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        newListing.getId()
                                )
                        );
        List<String> oldCategoryPath = categoryPath(oldWithCategories);
        List<String> newCategoryPath = categoryPath(newWithCategories);

        if (!oldCategoryPath.equals(newCategoryPath)) {
            throw new YagaHidingPreconditionException(
                    "Replacement Yaga listing category path must match the old listing"
            );
        }

        return new YagaHidingDraftSnapshot(
                oldListing.getId(),
                newListing.getId(),
                product.getId(),
                oldListing.getShopSlug(),
                oldListing.getExternalListingId(),
                oldListing.getProductSlug(),
                oldListing.getExternalUrl(),
                newListing.getExternalListingId(),
                newListing.getProductSlug(),
                newListing.getExternalUrl(),
                product.getDescription(),
                product.getAskingPrice(),
                product.getCondition(),
                oldCategoryPath,
                newCategoryPath,
                productImages.size(),
                newListing.getImages().size()
        );
    }

    private List<String> categoryPath(MarketplaceListing listing) {
        return listing.getCategories()
                .stream()
                .sorted(Comparator.comparingInt(
                        MarketplaceListingCategory::getCategoryLevel
                ))
                .map(MarketplaceListingCategory::getTitle)
                .toList();
    }

    private int linkedImageCount(MarketplaceListing listing) {
        return (int) listing.getImages()
                .stream()
                .filter(image -> image.getProductImage() != null)
                .count();
    }

    private void verifyNewListingWithYaga(
            YagaHidingDraftSnapshot snapshot
    ) {
        YagaImportedProductData data =
                pageDataClient.getProduct(snapshot.newExternalUrl());

        if (!snapshot.shopSlug().equals(data.shopSlug()) ||
                !snapshot.newProductSlug().equals(data.productSlug()) ||
                !snapshot.newExternalListingId()
                        .equals(data.externalId().toString()) ||
                !sameText(snapshot.productDescription(),
                        data.description()) ||
                !samePrice(snapshot.productPrice(), data.price()) ||
                mapCondition(data.condition()) !=
                        snapshot.productCondition() ||
                !snapshot.productCategoryPath().equals(
                        data.categoryPath()
                                .stream()
                                .map(YagaImportedProductData.Category::title)
                                .toList()
                ) ||
                data.images().size() !=
                        snapshot.newListingImageCount()) {
            throw new YagaPublicationReconciliationConflictException(
                    "Replacement Yaga listing does not match source product",
                    Map.of(
                            "newListingId",
                            snapshot.newListingId().toString()
                    )
            );
        }
    }

    private boolean oldListingMatches(
            YagaHidingDraftSnapshot snapshot,
            YagaImportedProductData data
    ) {
        return data != null &&
                snapshot.shopSlug().equals(data.shopSlug()) &&
                snapshot.oldProductSlug().equals(data.productSlug()) &&
                snapshot.oldExternalListingId()
                        .equals(data.externalId().toString());
    }

    private ProductCondition mapCondition(
            YagaImportedProductData.Condition condition
    ) {
        if (condition == null || condition.id() == null) {
            throw new YagaPublishingDataInvalidException(
                    "Published Yaga condition is missing"
            );
        }

        return switch (condition.id().intValue()) {
            case 1 -> ProductCondition.NEW_WITHOUT_TAGS;
            case 2 -> ProductCondition.VERY_GOOD;
            case 3 -> ProductCondition.GOOD;
            case 4 -> ProductCondition.SATISFACTORY;
            default -> throw new YagaPublishingDataInvalidException(
                    "Published Yaga condition is unsupported"
            );
        };
    }

    private void validateTarget(
            YagaHidingDraftSnapshot snapshot,
            YagaHideControlInspection inspection
    ) {
        YagaHideTargetDiagnostics diagnostics =
                inspection.targetDiagnostics();
        int targetEvidenceCount =
                targetEvidenceCount(diagnostics);

        if (!inspection.readyForConfirmation() ||
                targetEvidenceCount < 2 ||
                !snapshot.oldExternalListingId()
                        .equals(inspection.targetExternalListingId()) ||
                !snapshot.oldProductSlug()
                        .equals(inspection.targetProductSlug()) ||
                !YagaPublicProductUrlValidator
                        .isExpectedPublicProductUrl(
                        inspection.currentUrl(),
                        snapshot.shopSlug(),
                        snapshot.oldProductSlug()
                )) {
            if (isOwnerSessionMissing(snapshot, inspection)) {
                throw new YagaHidingAuthException(
                        "Yaga hiding page is opened without owner controls",
                        diagnosticDetails(snapshot, inspection)
                );
            }
            throw new YagaHidingPreconditionException(
                    "Yaga hide preparation target is not the old listing",
                    diagnosticDetails(snapshot, inspection)
            );
        }
    }

    private boolean isOwnerSessionMissing(
            YagaHidingDraftSnapshot snapshot,
            YagaHideControlInspection inspection
    ) {
        YagaHideTargetDiagnostics diagnostics =
                inspection.targetDiagnostics();

        return diagnostics != null &&
                targetEvidenceCount(diagnostics) >= 2 &&
                YagaPublicProductUrlValidator
                        .isExpectedPublicProductUrl(
                                inspection.currentUrl(),
                                snapshot.shopSlug(),
                                snapshot.oldProductSlug()
                        ) &&
                !diagnostics.ownerControlsVisible();
    }

    private int targetEvidenceCount(
            YagaHideTargetDiagnostics diagnostics
    ) {
        if (diagnostics == null) {
            return 0;
        }

        int count = 0;
        if (diagnostics.managementUrlContainsExpectedTarget()) {
            count++;
        }
        if (diagnostics.canonicalProductUrlInDom()) {
            count++;
        }
        if (diagnostics.expectedProductSlugInDom() ||
                diagnostics.expectedExternalListingIdInDom()) {
            count++;
        }
        return count;
    }

    private Map<String, String> diagnosticDetails(
            YagaHidingDraftSnapshot snapshot,
            YagaHideControlInspection inspection
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        YagaHideTargetDiagnostics diagnostics =
                inspection.targetDiagnostics();

        details.put(
                "requestedOldListingId",
                snapshot.oldListingId().toString()
        );
        details.put("expectedShopSlug", snapshot.shopSlug());
        details.put(
                "expectedProductSlug",
                snapshot.oldProductSlug()
        );
        details.put(
                "expectedExternalListingId",
                snapshot.oldExternalListingId()
        );
        details.put(
                "inspectionTargetProductSlug",
                safe(inspection.targetProductSlug())
        );
        details.put(
                "inspectionTargetExternalListingId",
                safe(inspection.targetExternalListingId())
        );
        details.put(
                "candidateCount",
                Integer.toString(inspection.candidateCount())
        );
        details.put(
                "visibleCandidateCount",
                Integer.toString(inspection.visibleCandidateCount())
        );
        details.put(
                "enabledCandidateCount",
                Integer.toString(inspection.enabledCandidateCount())
        );
        details.put(
                "targetEvidenceCount",
                Integer.toString(targetEvidenceCount(diagnostics))
        );

        if (diagnostics != null) {
            details.put(
                    "requestedManagementUrl",
                    safe(diagnostics.requestedManagementUrl())
            );
            details.put("currentUrl", safe(diagnostics.currentUrl()));
            details.put("pageTitle", safe(diagnostics.pageTitle()));
            details.put(
                    "resolvedAuthStatePath",
                    safe(diagnostics.resolvedAuthStatePath())
            );
            details.put(
                    "authStateFileExists",
                    Boolean.toString(diagnostics.authStateFileExists())
            );
            details.put(
                    "authStateFileReadable",
                    Boolean.toString(diagnostics.authStateFileReadable())
            );
            details.put(
                    "authStateFileSize",
                    Long.toString(diagnostics.authStateFileSize())
            );
            details.put(
                    "expectedProductSlugInDom",
                    Boolean.toString(
                            diagnostics.expectedProductSlugInDom()
                    )
            );
            details.put(
                    "expectedExternalListingIdInDom",
                    Boolean.toString(
                            diagnostics.expectedExternalListingIdInDom()
                    )
            );
            details.put(
                    "canonicalProductUrlInDom",
                    Boolean.toString(
                            diagnostics.canonicalProductUrlInDom()
                    )
            );
            details.put(
                    "managementUrlContainsExpectedTarget",
                    Boolean.toString(
                            diagnostics.managementUrlContainsExpectedTarget()
                    )
            );
            details.put(
                    "editControlVisible",
                    Boolean.toString(
                            diagnostics.editControlVisible()
                    )
            );
            details.put(
                    "hideControlVisible",
                    Boolean.toString(
                            diagnostics.hideControlVisible()
                    )
            );
            details.put(
                    "ownerControlsVisible",
                    Boolean.toString(
                            diagnostics.ownerControlsVisible()
                    )
            );
            details.put(
                    "screenshotPath",
                    diagnostics.screenshotPath() == null
                            ? ""
                            : diagnostics.screenshotPath().toString()
            );
            details.put(
                    "managementControls",
                    diagnostics.managementControls().toString()
            );
        } else {
            details.put("currentUrl", safe(inspection.currentUrl()));
        }

        return details;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean sameNonBlank(String first, String second) {
        return !isBlank(first) &&
                !isBlank(second) &&
                first.equals(second);
    }

    private boolean sameText(String expected, String actual) {
        return expected != null &&
                actual != null &&
                expected.trim().equals(actual.trim());
    }

    private boolean samePrice(
            BigDecimal actual,
            BigDecimal expected
    ) {
        return actual != null &&
                expected != null &&
                actual.compareTo(expected) == 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record YagaHidingDraftSnapshot(
            Long oldListingId,
            Long newListingId,
            Long productId,
            String shopSlug,
            String oldExternalListingId,
            String oldProductSlug,
            String oldExternalUrl,
            String newExternalListingId,
            String newProductSlug,
            String newExternalUrl,
            String productDescription,
            BigDecimal productPrice,
            ProductCondition productCondition,
            List<String> productCategoryPath,
            List<String> newListingCategoryPath,
            int productImageCount,
            int newListingImageCount
    ) {

        private YagaHidingDraftData toDraft() {
            return new YagaHidingDraftData(
                    oldListingId,
                    newListingId,
                    productId,
                    shopSlug,
                    oldExternalListingId,
                    oldProductSlug,
                    oldExternalUrl,
                    newExternalListingId,
                    newProductSlug,
                    newExternalUrl
            );
        }
    }
}
