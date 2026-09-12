package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublicationSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.YagaPublicationReconciliationService;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRunNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true'"
)
public class YagaRefreshExecutionService {

    private final YagaRefreshRunRepository runRepository;
    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final ObjectProvider<YagaPublicationSessionManager>
            publicationSessionManagerProvider;
    private final ObjectProvider<YagaPublicationReconciliationService>
            reconciliationServiceProvider;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Object publicationPreparationLock = new Object();

    public YagaRefreshExecutionService(
            YagaRefreshRunRepository runRepository,
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            ObjectProvider<YagaPublicationSessionManager>
                    publicationSessionManagerProvider,
            ObjectProvider<YagaPublicationReconciliationService>
                    reconciliationServiceProvider,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.runRepository = runRepository;
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.publicationSessionManagerProvider =
                publicationSessionManagerProvider;
        this.reconciliationServiceProvider = reconciliationServiceProvider;
        this.transactionTemplate = new TransactionTemplate(
                transactionManager
        );
        this.clock = clock;
    }

    public YagaRefreshPublicationPreparationResponse preparePublication(
            UUID runId,
            UUID jobId
    ) {
        synchronized (publicationPreparationLock) {
            return preparePublicationLocked(runId, jobId);
        }
    }

    private YagaRefreshPublicationPreparationResponse preparePublicationLocked(
            UUID runId,
            UUID jobId
    ) {
        YagaPublicationSessionManager sessionManager =
                requirePublicationSessionManager();

        ExistingPreparation existing =
                transactionTemplate.execute(status ->
                        validateAndMarkPublicationPreparing(runId, jobId)
                );

        if (existing == null) {
            throw new IllegalStateException(
                    "Yaga refresh publication preparation transaction returned no result"
            );
        }

        if (existing.preparationId() != null) {
            ExistingPreparationDecision decision =
                    classifyExistingPreparation(
                            sessionManager,
                            existing
                    );

            if (decision.response() != null) {
                return decision.response();
            }

            if (decision.resultResponse() != null) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh publication already has a terminal result"
                );
            }
        }

        try {
            YagaPublicationPreparationResponse preparation =
                    sessionManager.prepare(existing.oldListingId());
            YagaPublishReadinessResponse readiness =
                    sessionManager.publishReadiness(
                            preparation.preparationId()
                    );

            transactionTemplate.executeWithoutResult(status ->
                    savePublicationPrepared(
                            runId,
                            jobId,
                            preparation
                    )
            );

            return new YagaRefreshPublicationPreparationResponse(
                    runId,
                    jobId,
                    existing.productId(),
                    existing.oldListingId(),
                    YagaRefreshJobStatus.PUBLISHING,
                    preparation.preparationId(),
                    preparation.status().name(),
                    preparation.confirmationToken(),
                    preparation.expiresAt(),
                    readiness
            );

        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status ->
                    resetAfterPreClickPreparationFailure(
                            runId,
                            jobId,
                            "PUBLICATION_PREPARATION_FAILED",
                            "Yaga refresh publication preparation failed"
                    )
            );
            throw exception;
        }
    }

    public YagaPublishReadinessResponse publicationReadiness(
            UUID runId,
            UUID jobId
    ) {
        UUID preparationId = transactionTemplate.execute(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() != YagaRefreshJobStatus.PUBLISHING ||
                    job.getPublicationPreparationId() == null) {
                throw new YagaRefreshInvalidStateException(
                        "PUBLICATION_SESSION_NOT_AVAILABLE"
                );
            }
            return job.getPublicationPreparationId();
        });

        try {
            return requirePublicationSessionManager()
                    .publishReadiness(preparationId);
        } catch (RuntimeException exception) {
            throw new YagaRefreshInvalidStateException(
                    "PUBLICATION_SESSION_NOT_AVAILABLE"
            );
        }
    }

    private ExistingPreparationDecision classifyExistingPreparation(
            YagaPublicationSessionManager sessionManager,
            ExistingPreparation existing
    ) {
        try {
            YagaPublicationPreparationStatusResponse status =
                    sessionManager.status(existing.preparationId());

            if (existing.confirmStartedAt() != null &&
                    status.status() != YagaPublicationStatus.PUBLISHED &&
                    status.status() !=
                            YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED) {
                markPublicationResultUnknown(
                        existing.runId(),
                        existing.jobId(),
                        "PUBLICATION_CONFIRM_ALREADY_STARTED",
                        "Yaga publication confirmation already started; use publication reconciliation"
                );
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh publication confirmation already started; use publication reconciliation"
                );
            }

            if (status.status() == YagaPublicationStatus.AWAITING_CONFIRMATION) {
                YagaPublishReadinessResponse readiness =
                        sessionManager.publishReadiness(
                                existing.preparationId()
                        );
                return ExistingPreparationDecision.response(
                        new YagaRefreshPublicationPreparationResponse(
                                existing.runId(),
                                existing.jobId(),
                                existing.productId(),
                                existing.oldListingId(),
                                YagaRefreshJobStatus.PUBLISHING,
                                existing.preparationId(),
                                status.status().name(),
                                null,
                                status.expiresAt(),
                                readiness
                        )
                );
            }

            if (status.status() == YagaPublicationStatus.EXPIRED ||
                    status.status() == YagaPublicationStatus.CANCELLED) {
                sessionManager.cancel(existing.preparationId());
                return ExistingPreparationDecision.recreate(
                        "PUBLICATION_SESSION_" + status.status().name(),
                        "Yaga publication session is no longer active; prepare publication again"
                );
            }

            if (status.status() == YagaPublicationStatus.PUBLISHED ||
                    status.status() ==
                            YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED) {
                return ExistingPreparationDecision.result(
                        linkExistingPublishedSessionResult(
                                existing,
                                status
                        )
                );
            }

            if (status.status() == YagaPublicationStatus.PUBLISHING ||
                    status.status() ==
                            YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN) {
                markPublicationResultUnknown(
                        existing.runId(),
                        existing.jobId(),
                        "PUBLICATION_RESULT_UNKNOWN",
                        "Yaga publication may already have been attempted; use publication reconciliation"
                );
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh publication may already have been attempted; use publication reconciliation"
                );
            }

            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh publication cannot be prepared from session status: " +
                            status.status()
            );

        } catch (YagaPublicationPreparationNotFoundException exception) {
            if (existing.confirmStartedAt() != null) {
                markPublicationResultUnknown(
                        existing.runId(),
                        existing.jobId(),
                        "PUBLICATION_SESSION_NOT_AVAILABLE_AFTER_CONFIRM_STARTED",
                        "Yaga publication session is not available after confirmation started; use publication reconciliation"
                );
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh publication session is not available after confirmation started; use publication reconciliation"
                );
            }

            return ExistingPreparationDecision.recreate(
                    "PUBLICATION_SESSION_NOT_AVAILABLE",
                    "Yaga publication session is not available; prepare publication again"
            );
        }
    }

    public YagaRefreshPublicationResultResponse confirmPublication(
            UUID runId,
            UUID jobId,
            YagaRefreshPublicationConfirmRequest request
    ) {
        PreConfirmState state =
                transactionTemplate.execute(status ->
                        validateBeforeConfirm(runId, jobId)
                );

        if (state == null) {
            throw new IllegalStateException(
                    "Yaga refresh publication confirm transaction returned no result"
            );
        }

        if (state.terminalResponse() != null) {
            return state.terminalResponse();
        }

        YagaPublicationSessionManager sessionManager =
                requirePublicationSessionManager();

        YagaPublishReadinessResponse readiness =
                sessionManager.publishReadiness(state.preparationId());
        if (!readiness.readyForConfirmation()) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh publication is not ready for confirmation"
            );
        }

        transactionTemplate.executeWithoutResult(status ->
                markPublicationConfirmStarted(runId, jobId)
        );

        YagaPublicationConfirmResponse publicationResponse =
                sessionManager.confirm(
                        state.preparationId(),
                        new YagaPublicationConfirmRequest(
                                request.confirmationToken(),
                                request.confirmationPhrase()
                        )
                );

        return transactionTemplate.execute(status ->
                savePublicationConfirmResult(
                        runId,
                        jobId,
                        publicationResponse
                )
        );
    }

    public YagaRefreshPublicationResultResponse reconcilePublication(
            UUID runId,
            UUID jobId,
            YagaRefreshPublicationReconcileRequest request
    ) {
        Long oldListingId = transactionTemplate.execute(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() == YagaRefreshJobStatus.NEW_LISTING_CONFIRMED) {
                return null;
            }
            if (job.getStatus() != YagaRefreshJobStatus.RESULT_UNKNOWN &&
                    job.getStatus() != YagaRefreshJobStatus.PUBLISHING) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh publication cannot be reconciled from status: " +
                                job.getStatus()
                );
            }
            validateSnapshot(job);
            return job.getOldListing().getId();
        });

        if (oldListingId == null) {
            return transactionTemplate.execute(status ->
                    resultResponse(requireLockedJob(runId, jobId))
            );
        }

        YagaListingPublicationReconcileResponse response =
                requireReconciliationService().reconcile(
                        oldListingId,
                        new YagaListingPublicationReconcileRequest(
                                request.publicUrl()
                        )
                );

        return transactionTemplate.execute(status ->
                saveReconciledPublication(runId, jobId, response)
        );
    }

    private ExistingPreparation validateAndMarkPublicationPreparing(
            UUID runId,
            UUID jobId
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        validateRunForExecution(job.getRun());

        if (job.getStatus() == YagaRefreshJobStatus.PUBLISHING &&
                job.getPublicationPreparationId() != null) {
            validateSnapshot(job);
            return new ExistingPreparation(
                    job.getRun().getId(),
                    job.getId(),
                    job.getProduct().getId(),
                    job.getOldListing().getId(),
                    job.getPublicationPreparationId(),
                    job.getPublicationConfirmStartedAt()
            );
        }

        if (job.getStatus() != YagaRefreshJobStatus.SELECTED) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh job cannot prepare publication from status: " +
                            job.getStatus()
            );
        }

        validateSnapshot(job);
        validateNoReplacementListing(job);
        job.setStatus(YagaRefreshJobStatus.PUBLISHING);
        job.setPublicationStatus(YagaPublicationStatus.PREPARING.name());
        job.setLastErrorCode(null);
        job.setLastSafeErrorMessage(null);

        return new ExistingPreparation(
                job.getRun().getId(),
                job.getId(),
                job.getProduct().getId(),
                job.getOldListing().getId(),
                null,
                null
        );
    }

    private void savePublicationPrepared(
            UUID runId,
            UUID jobId,
            YagaPublicationPreparationResponse preparation
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        job.setPublicationPreparationId(preparation.preparationId());
        job.setPublicationStatus(preparation.status().name());
        job.setPublicationPreparedAt(clock.instant());
        job.setLastErrorCode(null);
        job.setLastSafeErrorMessage(null);
    }

    private void resetAfterPreClickPreparationFailure(
            UUID runId,
            UUID jobId,
            String errorCode,
            String safeMessage
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        if (job.getStatus() == YagaRefreshJobStatus.PUBLISHING &&
                job.getPublicationPreparationId() == null) {
            job.setStatus(YagaRefreshJobStatus.SELECTED);
            job.setPublicationStatus(null);
        }
        job.setLastErrorCode(errorCode);
        job.setLastSafeErrorMessage(safeMessage);
    }

    private PreConfirmState validateBeforeConfirm(
            UUID runId,
            UUID jobId
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);

        if (job.getStatus() == YagaRefreshJobStatus.NEW_LISTING_CONFIRMED) {
            return new PreConfirmState(
                    null,
                    resultResponse(job)
            );
        }

        validateRunForExecution(job.getRun());
        if (job.getStatus() != YagaRefreshJobStatus.PUBLISHING ||
                job.getPublicationPreparationId() == null) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh job cannot confirm publication from status: " +
                                job.getStatus()
                );
        }

        validateSnapshot(job);
        return new PreConfirmState(
                job.getPublicationPreparationId(),
                null
        );
    }

    private YagaRefreshPublicationResultResponse savePublicationConfirmResult(
            UUID runId,
            UUID jobId,
            YagaPublicationConfirmResponse response
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        job.setPublicationStatus(response.status().name());
        job.setPublicationConfirmedAt(clock.instant());

        if (response.status() == YagaPublicationStatus.PUBLISHED) {
            MarketplaceListing newListing =
                    findPublishedListingForJob(job, response);
            linkConfirmedNewListing(job, newListing);
            job.getRun().setStatus(YagaRefreshRunStatus.PROCESSING);
        } else {
            job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
            job.setNewProductUrl(response.newProductUrl());
            job.setNewShopSlug(response.newShopSlug());
            job.setNewProductSlug(response.newProductSlug());
            job.setLastErrorCode("PUBLICATION_RESULT_UNKNOWN");
            job.setLastSafeErrorMessage(
                    "Yaga publication result could not be confirmed"
            );
        }

        return resultResponse(job);
    }

    private void markPublicationConfirmStarted(
            UUID runId,
            UUID jobId
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        if (job.getStatus() != YagaRefreshJobStatus.PUBLISHING ||
                job.getPublicationPreparationId() == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh publication confirm cannot be started from status: " +
                            job.getStatus()
            );
        }
        if (job.getPublicationConfirmStartedAt() == null) {
            job.setPublicationConfirmStartedAt(clock.instant());
        }
    }

    private void markPublicationResultUnknown(
            UUID runId,
            UUID jobId,
            String errorCode,
            String safeMessage
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() != YagaRefreshJobStatus.NEW_LISTING_CONFIRMED) {
                job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                job.setPublicationStatus(
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN.name()
                );
                job.setLastErrorCode(errorCode);
                job.setLastSafeErrorMessage(safeMessage);
            }
        });
    }

    private YagaRefreshPublicationResultResponse saveReconciledPublication(
            UUID runId,
            UUID jobId,
            YagaListingPublicationReconcileResponse response
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        MarketplaceListing newListing =
                listingRepository.findById(response.newListingId())
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        response.newListingId()
                                )
                        );
        linkConfirmedNewListing(job, newListing);
        job.setPublicationStatus(response.status().name());
        job.setPublicationConfirmedAt(clock.instant());
        job.getRun().setStatus(YagaRefreshRunStatus.PROCESSING);
        return resultResponse(job);
    }

    private void linkConfirmedNewListing(
            YagaRefreshJob job,
            MarketplaceListing newListing
    ) {
        if (!job.getProduct().getId()
                .equals(newListing.getProduct().getId())) {
            throw new YagaRefreshInvalidStateException(
                    "Published replacement listing belongs to another product"
            );
        }

        if (job.getOldListing().getId().equals(newListing.getId())) {
            throw new YagaRefreshInvalidStateException(
                    "Published replacement listing must differ from old listing"
            );
        }

        job.setNewListing(newListing);
        job.setNewExternalListingId(newListing.getExternalListingId());
        job.setNewShopSlug(newListing.getShopSlug());
        job.setNewProductSlug(newListing.getProductSlug());
        job.setNewProductUrl(newListing.getExternalUrl());
        job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        job.setLastErrorCode(null);
        job.setLastSafeErrorMessage(null);
    }

    private MarketplaceListing findPublishedListingForJob(
            YagaRefreshJob job,
            YagaPublicationConfirmResponse response
    ) {
        MarketplaceListing listing =
                listingRepository
                        .findByMarketplaceAndShopSlugAndProductSlug(
                                Marketplace.YAGA,
                                response.newShopSlug(),
                                response.newProductSlug()
                        )
                        .orElseThrow(() ->
                                new YagaRefreshInvalidStateException(
                                        "Published replacement listing was not saved"
                                )
                        );

        if (!job.getProduct().getId()
                .equals(listing.getProduct().getId())) {
            throw new YagaRefreshInvalidStateException(
                    "Published replacement listing belongs to another product"
            );
        }

        return listing;
    }

    private YagaRefreshPublicationResultResponse linkExistingPublishedSessionResult(
            ExistingPreparation existing,
            YagaPublicationPreparationStatusResponse status
    ) {
        return transactionTemplate.execute(transactionStatus -> {
            YagaRefreshJob job =
                    requireLockedJob(existing.runId(), existing.jobId());
            List<MarketplaceListing> publishedListings =
                    listingRepository.findAllByProductIdAndMarketplaceAndStatus(
                            job.getProduct().getId(),
                            Marketplace.YAGA,
                            MarketplaceListingStatus.PUBLISHED
                    );
            List<MarketplaceListing> replacements = publishedListings.stream()
                    .filter(listing ->
                            !listing.getId()
                                    .equals(job.getOldListing().getId()))
                    .toList();

            if (replacements.size() != 1) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh published replacement listing could not be resolved"
                );
            }

            MarketplaceListing replacement = replacements.getFirst();
            linkConfirmedNewListing(job, replacement);
            job.setPublicationStatus(status.status().name());
            if (job.getPublicationConfirmedAt() == null) {
                job.setPublicationConfirmedAt(clock.instant());
            }
            job.getRun().setStatus(YagaRefreshRunStatus.PROCESSING);
            return resultResponse(job);
        });
    }

    private void validateRunForExecution(YagaRefreshRun run) {
        if (run.getMode() != YagaRefreshRunMode.MANUAL ||
                run.getStatus() != YagaRefreshRunStatus.AWAITING_CONFIRMATION) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh publication execution requires a manual run awaiting confirmation"
            );
        }
    }

    private void validateSnapshot(YagaRefreshJob job) {
        MarketplaceListing listing =
                listingRepository.findByIdWithImagesAndProductImages(
                                job.getOldListing().getId()
                        )
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        job.getOldListing().getId()
                                )
                        );

        if (listing.getMarketplace() != Marketplace.YAGA ||
                listing.getStatus() != MarketplaceListingStatus.PUBLISHED ||
                !listing.isCurrent() ||
                listing.getHiddenAt() != null ||
                listing.getDeletedAt() != null ||
                listing.getProduct().getStatus() == ProductStatus.ARCHIVED) {

            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh old listing is no longer eligible for publication"
            );
        }

        if (!Objects.equals(job.getProduct().getId(),
                listing.getProduct().getId()) ||
                !Objects.equals(job.getOldExternalListingId(),
                        listing.getExternalListingId()) ||
                !Objects.equals(job.getOldShopSlug(),
                        listing.getShopSlug()) ||
                !Objects.equals(job.getOldProductSlug(),
                        listing.getProductSlug()) ||
                !Objects.equals(job.getOldPublicUrl(),
                        listing.getExternalUrl()) ||
                !Objects.equals(job.getProductTitle(),
                        listing.getProduct().getTitle())) {

            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh job snapshot no longer matches source listing"
            );
        }

        List<MarketplaceListingImage> listingImages =
                listing.getImages()
                        .stream()
                        .sorted(Comparator.comparingInt(
                                MarketplaceListingImage::getDisplayOrder
                        ))
                        .toList();
        List<ProductImage> productImages =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                listing.getProduct().getId()
                        );

        if (listingImages.isEmpty() ||
                productImages.isEmpty() ||
                listingImages.stream().anyMatch(image ->
                        image.getProductImage() == null) ||
                !Objects.equals(
                        listingImages.size(),
                        job.getExpectedListingImageCount()
                ) ||
                !Objects.equals(
                        productImages.size(),
                        job.getExpectedProductImageCount()
                ) ||
                listingImages.size() != productImages.size()) {

            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh source listing images are not fully archived"
            );
        }
    }

    private void validateNoReplacementListing(YagaRefreshJob job) {
        List<MarketplaceListing> publishedListings =
                listingRepository.findAllByProductIdAndMarketplaceAndStatus(
                        job.getProduct().getId(),
                        Marketplace.YAGA,
                        MarketplaceListingStatus.PUBLISHED
                );

        boolean hasReplacement = publishedListings.stream()
                .anyMatch(listing ->
                        !listing.getId().equals(job.getOldListing().getId()));

        if (hasReplacement) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh job already has a replacement listing"
            );
        }
    }

    private YagaRefreshJob requireLockedJob(UUID runId, UUID jobId) {
        YagaRefreshRun run = runRepository.findForUpdateWithJobsById(runId)
                .orElseThrow(() ->
                        new YagaRefreshRunNotFoundException(runId));

        return run.getJobs()
                .stream()
                .filter(job -> job.getId().equals(jobId))
                .findFirst()
                .orElseThrow(() ->
                        new YagaRefreshInvalidStateException(
                                "Yaga refresh job was not found in run"
                        )
                );
    }

    private YagaPublicationSessionManager requirePublicationSessionManager() {
        YagaPublicationSessionManager sessionManager =
                publicationSessionManagerProvider.getIfAvailable();
        if (sessionManager == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga publishing workflow must be enabled for refresh publication execution"
            );
        }
        return sessionManager;
    }

    private YagaPublicationReconciliationService requireReconciliationService() {
        YagaPublicationReconciliationService reconciliationService =
                reconciliationServiceProvider.getIfAvailable();
        if (reconciliationService == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga publication reconciliation must be enabled for refresh recovery"
            );
        }
        return reconciliationService;
    }

    private YagaRefreshPublicationResultResponse resultResponse(
            YagaRefreshJob job
    ) {
        return new YagaRefreshPublicationResultResponse(
                job.getRun().getId(),
                job.getId(),
                job.getRun().getStatus(),
                job.getStatus(),
                job.getPublicationPreparationId(),
                job.getOldListing().getId(),
                job.getNewListing() == null
                        ? null
                        : job.getNewListing().getId(),
                job.getNewExternalListingId(),
                job.getNewShopSlug(),
                job.getNewProductSlug(),
                job.getNewProductUrl(),
                job.getPublicationConfirmedAt(),
                job.getLastErrorCode(),
                job.getLastSafeErrorMessage()
        );
    }

    private record ExistingPreparationDecision(
            YagaRefreshPublicationPreparationResponse response,
            YagaRefreshPublicationResultResponse resultResponse,
            String errorCode,
            String safeMessage
    ) {
        private static ExistingPreparationDecision response(
                YagaRefreshPublicationPreparationResponse response
        ) {
            return new ExistingPreparationDecision(
                    response,
                    null,
                    null,
                    null
            );
        }

        private static ExistingPreparationDecision result(
                YagaRefreshPublicationResultResponse resultResponse
        ) {
            return new ExistingPreparationDecision(
                    null,
                    resultResponse,
                    null,
                    null
            );
        }

        private static ExistingPreparationDecision recreate(
                String errorCode,
                String safeMessage
        ) {
            return new ExistingPreparationDecision(
                    null,
                    null,
                    errorCode,
                    safeMessage
            );
        }
    }

    private record ExistingPreparation(
            UUID runId,
            UUID jobId,
            Long productId,
            Long oldListingId,
            UUID preparationId,
            Instant confirmStartedAt
    ) {
    }

    private record PreConfirmState(
            UUID preparationId,
            YagaRefreshPublicationResultResponse terminalResponse
    ) {
    }
}
