package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationForbiddenException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.YagaHidingSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingPreconditionException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshHideFinalizationException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshJobNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRunNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublicProductUrlValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.hide-execution-enabled:false}' == 'true' " +
                "&& '${yaga.hiding.enabled:false}' == 'true'"
)
public class YagaRefreshHidingExecutionService {

    private final YagaRefreshRunRepository runRepository;
    private final MarketplaceListingRepository listingRepository;
    private final ObjectProvider<YagaHidingSessionManager> sessionProvider;
    private final YagaPageDataClient pageDataClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Object preparationLock = new Object();
    private final Object confirmationLock = new Object();

    public YagaRefreshHidingExecutionService(
            YagaRefreshRunRepository runRepository,
            MarketplaceListingRepository listingRepository,
            ObjectProvider<YagaHidingSessionManager> sessionProvider,
            YagaPageDataClient pageDataClient,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.runRepository = runRepository;
        this.listingRepository = listingRepository;
        this.sessionProvider = sessionProvider;
        this.pageDataClient = pageDataClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public YagaRefreshHidePreparationResponse prepare(
            UUID runId,
            UUID jobId
    ) {
        synchronized (preparationLock) {
            return prepareLocked(runId, jobId);
        }
    }

    private YagaRefreshHidePreparationResponse prepareLocked(
            UUID runId,
            UUID jobId
    ) {
        YagaHidingSessionManager manager = requireSessionManager();
        ExistingHide existing = transactionTemplate.execute(status ->
                validateForPrepare(runId, jobId)
        );
        if (existing == null) {
            throw new IllegalStateException("Hide preparation state was not loaded");
        }

        if (existing.preparationId() != null) {
            YagaRefreshHidePreparationResponse reused =
                    reuseOrClear(manager, existing);
            if (reused != null) {
                return reused;
            }
        }

        YagaHidePreparationResponse preparation = null;
        try {
            preparation = prepareSession(manager, existing.oldListingId());
            validatePreparedIdentity(existing, preparation);
            YagaHideReadinessResponse readiness =
                    inspectReadiness(manager, preparation.preparationId());
            if (!readiness.readyForConfirmation()) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga old listing is not ready for hide confirmation"
                );
            }
            YagaHidePreparationResponse successful = preparation;
            transactionTemplate.executeWithoutResult(status ->
                    savePrepared(runId, jobId, successful)
            );
            return preparationResponse(existing, preparation, readiness);
        } catch (RuntimeException exception) {
            if (preparation != null) {
                safelyCancel(manager, preparation.preparationId());
            }
            recordPreparationFailure(runId, jobId);
            throw exception;
        }
    }

    public YagaRefreshHideReadinessResponse readiness(
            UUID runId,
            UUID jobId
    ) {
        UUID preparationId = transactionTemplate.execute(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() != YagaRefreshJobStatus.HIDING_OLD ||
                    job.getHidePreparationId() == null ||
                    job.getHideConfirmStartedAt() != null) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh hide session is not available"
                );
            }
            validateListings(job, true);
            return job.getHidePreparationId();
        });
        return safeReadiness(inspectReadiness(
                requireSessionManager(),
                preparationId
        ));
    }

    public YagaRefreshHideResultResponse confirm(
            UUID runId,
            UUID jobId,
            YagaRefreshHideConfirmRequest request
    ) {
        synchronized (confirmationLock) {
            return confirmLocked(runId, jobId, request);
        }
    }

    private YagaRefreshHideResultResponse confirmLocked(
            UUID runId,
            UUID jobId,
            YagaRefreshHideConfirmRequest request
    ) {
        if (request == null || !"HIDE".equals(request.confirmationPhrase())) {
            throw new YagaRefreshRequestInvalidException(
                    "confirmationPhrase must be HIDE"
            );
        }

        ConfirmState state = transactionTemplate.execute(status ->
                validateBeforeConfirm(runId, jobId)
        );
        if (state.terminalResponse() != null) {
            return state.terminalResponse();
        }

        YagaHidingSessionManager manager = requireSessionManager();
        YagaHideReadinessResponse readiness =
                inspectReadiness(manager, state.preparationId());
        if (!readiness.readyForConfirmation()) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga old listing is not ready for hide confirmation"
            );
        }

        transactionTemplate.executeWithoutResult(status ->
                markConfirmStarted(runId, jobId, state.preparationId())
        );

        YagaHideConfirmResponse response;
        try {
            response = manager.confirmForRefresh(
                    state.preparationId(),
                    new YagaHideConfirmRequest(
                            request.confirmationToken(),
                            request.confirmationPhrase()
                    )
            );
        } catch (YagaPublicationForbiddenException exception) {
            resetInvalidConfirmation(runId, jobId, state.preparationId());
            throw new YagaRefreshRequestInvalidException(
                    "Invalid hide confirmation token"
            );
        } catch (RuntimeException exception) {
            handleConfirmFailure(manager, runId, jobId, state.preparationId());
            throw exception;
        }

        if (response.status() == YagaHidingStatus.HIDDEN &&
                response.hidden()) {
            return finalizeConfirmedHide(
                    runId,
                    jobId,
                    state.preparationId(),
                    response.hiddenAt()
            );
        }

        return markUnknown(
                runId,
                jobId,
                "HIDE_RESULT_UNKNOWN",
                "Yaga hide result could not be confirmed"
        );
    }

    public YagaRefreshHideResultResponse reconcile(
            UUID runId,
            UUID jobId
    ) {
        ReconcileState state = transactionTemplate.execute(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() == YagaRefreshJobStatus.COMPLETED) {
                return new ReconcileState(null, null, result(job));
            }
            boolean resultUnknown =
                    job.getStatus() == YagaRefreshJobStatus.RESULT_UNKNOWN &&
                            job.getHideStatus() ==
                                    YagaRefreshHideStatus.RESULT_UNKNOWN;
            boolean confirmStartedBeforeRecovery =
                    job.getStatus() == YagaRefreshJobStatus.HIDING_OLD &&
                            job.getHideStatus() ==
                                    YagaRefreshHideStatus.CONFIRMING &&
                            job.getHideConfirmStartedAt() != null;
            if (!resultUnknown && !confirmStartedBeforeRecovery) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga refresh hide can only be reconciled after an unknown result"
                );
            }
            ListingPair pair = validateListings(job, false);
            return new ReconcileState(
                    snapshot(job, pair),
                    job.getHidePreparationId(),
                    null
            );
        });
        if (state.terminalResponse() != null) {
            return state.terminalResponse();
        }

        YagaImportedProductData oldData;
        YagaImportedProductData newData;
        try {
            oldData = pageDataClient.getProduct(state.snapshot().oldUrl());
            newData = pageDataClient.getProduct(state.snapshot().newUrl());
        } catch (RuntimeException exception) {
            return markUnknown(
                    runId,
                    jobId,
                    "HIDE_RECONCILIATION_UNAVAILABLE",
                    "Yaga hide state could not be read"
            );
        }

        if (!matchesOldHidden(state.snapshot(), oldData) ||
                !matchesNewPublished(state.snapshot(), newData)) {
            return markUnknown(
                    runId,
                    jobId,
                    "HIDE_RECONCILIATION_MISMATCH",
                    "Yaga hide state could not be confirmed"
            );
        }

        return finalizeConfirmedHide(
                runId,
                jobId,
                state.preparationId(),
                oldData.hiddenAt()
        );
    }

    private ExistingHide validateForPrepare(UUID runId, UUID jobId) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        validateRun(job.getRun());
        if (job.getStatus() != YagaRefreshJobStatus.NEW_LISTING_CONFIRMED &&
                job.getStatus() != YagaRefreshJobStatus.HIDING_OLD) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh job cannot prepare hiding from status: " +
                            job.getStatus()
            );
        }
        ListingPair pair = validateListings(job, true);
        return new ExistingHide(
                runId,
                jobId,
                job.getProduct().getId(),
                pair.oldListing().getId(),
                pair.newListing().getId(),
                job.getHidePreparationId(),
                job.getHideConfirmStartedAt()
        );
    }

    private YagaRefreshHidePreparationResponse reuseOrClear(
            YagaHidingSessionManager manager,
            ExistingHide existing
    ) {
        try {
            YagaHidePreparationStatusResponse session =
                    manager.status(existing.preparationId());
            if (existing.confirmStartedAt() != null) {
                markUnknown(
                        existing.runId(),
                        existing.jobId(),
                        "HIDE_CONFIRM_ALREADY_STARTED",
                        "Yaga hide confirmation already started; use reconciliation"
                );
                throw new YagaRefreshInvalidStateException(
                        "Yaga hide confirmation already started; use reconciliation"
                );
            }
            if (session.status() == YagaHidingStatus.AWAITING_CONFIRMATION) {
                YagaHideReadinessResponse readiness =
                        inspectReadiness(manager, existing.preparationId());
                clearSuccessfulPreparationErrors(existing);
                return preparationResponse(existing, session, readiness);
            }
            if (session.status() == YagaHidingStatus.EXPIRED ||
                    session.status() == YagaHidingStatus.CANCELLED) {
                safelyCancel(manager, existing.preparationId());
                clearPreparation(existing, session.status());
                return null;
            }
            markUnknown(
                    existing.runId(),
                    existing.jobId(),
                    "HIDE_RESULT_UNKNOWN",
                    "Yaga hide may already have been attempted; use reconciliation"
            );
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide may already have been attempted; use reconciliation"
            );
        } catch (YagaPublicationPreparationNotFoundException exception) {
            if (existing.confirmStartedAt() != null) {
                markUnknown(
                        existing.runId(),
                        existing.jobId(),
                        "HIDE_SESSION_MISSING_AFTER_CONFIRM_STARTED",
                        "Yaga hide session is missing after confirmation started; use reconciliation"
                );
                throw new YagaRefreshInvalidStateException(
                        "Yaga hide session is missing after confirmation started; use reconciliation"
                );
            }
            clearPreparation(existing, null);
            return null;
        }
    }

    private void savePrepared(
            UUID runId,
            UUID jobId,
            YagaHidePreparationResponse preparation
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        validateListings(job, true);
        if (job.getHideConfirmStartedAt() != null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide confirmation already started"
            );
        }
        job.setStatus(YagaRefreshJobStatus.HIDING_OLD);
        job.setHidePreparationId(preparation.preparationId());
        job.setHideStatus(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
        job.setHidePreparedAt(clock.instant());
        job.setLastErrorCode(null);
        job.setLastSafeErrorMessage(null);
        job.setUpdatedAt(clock.instant());
    }

    private ConfirmState validateBeforeConfirm(UUID runId, UUID jobId) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        if (job.getStatus() == YagaRefreshJobStatus.COMPLETED &&
                job.getHideStatus() == YagaRefreshHideStatus.HIDDEN) {
            return new ConfirmState(null, result(job));
        }
        validateRun(job.getRun());
        if (job.getStatus() != YagaRefreshJobStatus.HIDING_OLD ||
                job.getHideStatus() !=
                        YagaRefreshHideStatus.AWAITING_CONFIRMATION ||
                job.getHidePreparationId() == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh job cannot confirm hiding from current state"
            );
        }
        if (job.getHideConfirmStartedAt() != null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide confirmation is already in progress"
            );
        }
        validateListings(job, true);
        return new ConfirmState(job.getHidePreparationId(), null);
    }

    private void markConfirmStarted(
            UUID runId,
            UUID jobId,
            UUID preparationId
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        validateListings(job, true);
        if (job.getStatus() != YagaRefreshJobStatus.HIDING_OLD ||
                !preparationId.equals(job.getHidePreparationId()) ||
                job.getHideConfirmStartedAt() != null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide confirmation cannot be started"
            );
        }
        job.setHideConfirmStartedAt(clock.instant());
        job.setHideStatus(YagaRefreshHideStatus.CONFIRMING);
        job.setUpdatedAt(clock.instant());
    }

    private YagaRefreshHideResultResponse completeHide(
            UUID runId,
            UUID jobId,
            UUID preparationId,
            Instant confirmedHiddenAt
    ) {
        YagaRefreshJob job = requireLockedJob(runId, jobId);
        if (job.getStatus() == YagaRefreshJobStatus.COMPLETED &&
                job.getHideStatus() == YagaRefreshHideStatus.HIDDEN) {
            return result(job);
        }
        if (job.getStatus() != YagaRefreshJobStatus.HIDING_OLD &&
                job.getStatus() != YagaRefreshJobStatus.RESULT_UNKNOWN) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh hide result cannot be completed"
            );
        }
        if (job.getHidePreparationId() != null && preparationId != null &&
                !job.getHidePreparationId().equals(preparationId)) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide preparation changed"
            );
        }
        ListingPair pair = validateListings(job, true);
        Instant hiddenAt = confirmedHiddenAt == null
                ? clock.instant()
                : confirmedHiddenAt;

        MarketplaceListing oldListing = pair.oldListing();
        MarketplaceListing newListing = pair.newListing();
        oldListing.setStatus(MarketplaceListingStatus.HIDDEN);
        oldListing.setCurrent(false);
        oldListing.setHiddenAt(hiddenAt);
        oldListing.setLastSyncedAt(clock.instant());
        listingRepository.saveAndFlush(oldListing);

        newListing.setStatus(MarketplaceListingStatus.PUBLISHED);
        newListing.setCurrent(true);
        newListing.setHiddenAt(null);
        newListing.setDeletedAt(null);
        newListing.setLastSyncedAt(clock.instant());
        listingRepository.save(newListing);

        job.setStatus(YagaRefreshJobStatus.COMPLETED);
        job.setHideStatus(YagaRefreshHideStatus.HIDDEN);
        job.setHideConfirmedAt(hiddenAt);
        job.setCompletedAt(clock.instant());
        job.setLastErrorCode(null);
        job.setLastSafeErrorMessage(null);
        job.setUpdatedAt(clock.instant());
        updateRunStatus(job.getRun());
        return result(job);
    }

    private YagaRefreshHideResultResponse finalizeConfirmedHide(
            UUID runId,
            UUID jobId,
            UUID preparationId,
            Instant confirmedHiddenAt
    ) {
        try {
            return transactionTemplate.execute(status ->
                    completeHide(
                            runId,
                            jobId,
                            preparationId,
                            confirmedHiddenAt
                    )
            );
        } catch (RuntimeException exception) {
            try {
                markUnknown(
                        runId,
                        jobId,
                        "DATABASE_FINALIZATION_FAILED",
                        "Yaga hide was confirmed, but local finalization requires reconciliation"
                );
            } catch (RuntimeException ignored) {
                // Preserve the safe outward error even if persistence is unavailable.
            }
            throw new YagaRefreshHideFinalizationException();
        }
    }

    private ListingPair validateListings(
            YagaRefreshJob job,
            boolean requireOldActive
    ) {
        if (job.getNewListing() == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh replacement listing is missing"
            );
        }
        MarketplaceListing oldListing = listingRepository
                .findByIdForUpdate(job.getOldListing().getId())
                .orElseThrow(() -> new MarketplaceListingNotFoundException(
                        job.getOldListing().getId()
                ));
        MarketplaceListing newListing = listingRepository
                .findByIdForUpdate(job.getNewListing().getId())
                .orElseThrow(() -> new MarketplaceListingNotFoundException(
                        job.getNewListing().getId()
                ));

        Long productId = job.getProduct().getId();
        if (!productId.equals(oldListing.getProduct().getId()) ||
                !productId.equals(newListing.getProduct().getId()) ||
                oldListing.getId().equals(newListing.getId()) ||
                oldListing.getMarketplace() != Marketplace.YAGA ||
                newListing.getMarketplace() != Marketplace.YAGA) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh old/new listing linkage is invalid"
            );
        }
        if (requireOldActive &&
                (oldListing.getStatus() != MarketplaceListingStatus.PUBLISHED ||
                        !oldListing.isCurrent() ||
                        oldListing.getHiddenAt() != null ||
                        oldListing.getDeletedAt() != null)) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh old listing is no longer current published"
            );
        }
        if (newListing.getStatus() != MarketplaceListingStatus.PUBLISHED ||
                newListing.getHiddenAt() != null ||
                newListing.getDeletedAt() != null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh replacement listing is not published"
            );
        }
        requireIdentity(oldListing);
        requireIdentity(newListing);
        if (!YagaPublicProductUrlValidator.isExpectedPublicProductUrl(
                oldListing.getExternalUrl(),
                oldListing.getShopSlug(),
                oldListing.getProductSlug()
        ) || !YagaPublicProductUrlValidator.isExpectedPublicProductUrl(
                newListing.getExternalUrl(),
                newListing.getShopSlug(),
                newListing.getProductSlug()
        )) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh listing public URL does not match identity"
            );
        }
        if (!oldListing.getExternalListingId().equals(
                job.getOldExternalListingId()) ||
                !oldListing.getShopSlug().equals(job.getOldShopSlug()) ||
                !oldListing.getProductSlug().equals(job.getOldProductSlug()) ||
                !oldListing.getExternalUrl().equals(job.getOldPublicUrl()) ||
                !newListing.getExternalListingId().equals(
                        job.getNewExternalListingId()) ||
                !newListing.getShopSlug().equals(job.getNewShopSlug()) ||
                !newListing.getProductSlug().equals(job.getNewProductSlug()) ||
                !newListing.getExternalUrl().equals(job.getNewProductUrl()) ||
                oldListing.getExternalListingId().equals(
                        newListing.getExternalListingId())) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh listing snapshot does not match current data"
            );
        }
        return new ListingPair(oldListing, newListing);
    }

    private void requireIdentity(MarketplaceListing listing) {
        if (isBlank(listing.getExternalListingId()) ||
                isBlank(listing.getShopSlug()) ||
                isBlank(listing.getProductSlug()) ||
                isBlank(listing.getExternalUrl())) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga listing identity is incomplete"
            );
        }
    }

    private void validateRun(YagaRefreshRun run) {
        if (run.getMode() != YagaRefreshRunMode.MANUAL ||
                run.getStatus() != YagaRefreshRunStatus.PROCESSING) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh run is not ready for hiding"
            );
        }
    }

    private void validatePreparedIdentity(
            ExistingHide expected,
            YagaHidePreparationResponse actual
    ) {
        if (!expected.oldListingId().equals(actual.oldListingId()) ||
                !expected.newListingId().equals(actual.newListingId()) ||
                !expected.productId().equals(actual.productId()) ||
                !actual.readyForConfirmation()) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide preparation target does not match refresh job"
            );
        }
    }

    private void clearPreparation(
            ExistingHide existing,
            YagaHidingStatus terminalStatus
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaRefreshJob job = requireLockedJob(
                    existing.runId(),
                    existing.jobId()
            );
            if (job.getHideConfirmStartedAt() != null) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga hide confirmation already started"
                );
            }
            job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
            job.setHidePreparationId(null);
            job.setHideStatus(terminalStatus == YagaHidingStatus.EXPIRED
                    ? YagaRefreshHideStatus.EXPIRED
                    : terminalStatus == YagaHidingStatus.CANCELLED
                    ? YagaRefreshHideStatus.CANCELLED
                    : YagaRefreshHideStatus.NOT_STARTED);
            job.setLastErrorCode("HIDE_SESSION_NOT_ACTIVE");
            job.setLastSafeErrorMessage(
                    "Yaga hide session is not active; prepare hiding again"
            );
            job.setUpdatedAt(clock.instant());
        });
    }

    private void clearSuccessfulPreparationErrors(ExistingHide existing) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaRefreshJob job = requireLockedJob(
                    existing.runId(),
                    existing.jobId()
            );
            if (!existing.preparationId().equals(job.getHidePreparationId()) ||
                    job.getStatus() != YagaRefreshJobStatus.HIDING_OLD ||
                    job.getHideStatus() !=
                            YagaRefreshHideStatus.AWAITING_CONFIRMATION ||
                    job.getHideConfirmStartedAt() != null) {
                throw new YagaRefreshInvalidStateException(
                        "Yaga hide preparation changed"
                );
            }
            job.setLastErrorCode(null);
            job.setLastSafeErrorMessage(null);
            job.setUpdatedAt(clock.instant());
        });
    }

    private void resetInvalidConfirmation(
            UUID runId,
            UUID jobId,
            UUID preparationId
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (preparationId.equals(job.getHidePreparationId()) &&
                    job.getStatus() == YagaRefreshJobStatus.HIDING_OLD) {
                job.setHideConfirmStartedAt(null);
                job.setHideStatus(YagaRefreshHideStatus.AWAITING_CONFIRMATION);
                job.setLastErrorCode("INVALID_HIDE_CONFIRMATION");
                job.setLastSafeErrorMessage("Invalid hide confirmation");
                job.setUpdatedAt(clock.instant());
            }
        });
    }

    private void recordPreparationFailure(UUID runId, UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getHideConfirmStartedAt() == null &&
                    job.getStatus() != YagaRefreshJobStatus.RESULT_UNKNOWN &&
                    job.getStatus() != YagaRefreshJobStatus.COMPLETED) {
                job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                job.setHidePreparationId(null);
                job.setHideStatus(YagaRefreshHideStatus.NOT_STARTED);
                job.setLastErrorCode("HIDE_PREPARATION_FAILED");
                job.setLastSafeErrorMessage(
                        "Yaga hide preparation failed before confirmation"
                );
                job.setUpdatedAt(clock.instant());
            }
        });
    }

    private void handleConfirmFailure(
            YagaHidingSessionManager manager,
            UUID runId,
            UUID jobId,
            UUID preparationId
    ) {
        try {
            YagaHidePreparationStatusResponse status =
                    manager.status(preparationId);
            if (status.status() == YagaHidingStatus.AWAITING_CONFIRMATION) {
                resetInvalidConfirmation(runId, jobId, preparationId);
                return;
            }
        } catch (RuntimeException ignored) {
            // Missing state after control entered the click path is ambiguous.
        }
        markUnknown(
                runId,
                jobId,
                "HIDE_RESULT_UNKNOWN",
                "Yaga hide result could not be confirmed"
        );
    }

    private YagaRefreshHideResultResponse markUnknown(
            UUID runId,
            UUID jobId,
            String code,
            String message
    ) {
        return transactionTemplate.execute(status -> {
            YagaRefreshJob job = requireLockedJob(runId, jobId);
            if (job.getStatus() != YagaRefreshJobStatus.COMPLETED) {
                job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                job.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN);
                job.setLastErrorCode(code);
                job.setLastSafeErrorMessage(message);
                job.setUpdatedAt(clock.instant());
                updateRunStatus(job.getRun());
            }
            return result(job);
        });
    }

    private void updateRunStatus(YagaRefreshRun run) {
        boolean unfinished = run.getJobs().stream().anyMatch(job ->
                job.getStatus() != YagaRefreshJobStatus.COMPLETED &&
                        job.getStatus() != YagaRefreshJobStatus.FAILED &&
                        job.getStatus() != YagaRefreshJobStatus.RESULT_UNKNOWN
        );
        if (unfinished) {
            run.setStatus(YagaRefreshRunStatus.PROCESSING);
            run.setCompletedAt(null);
            return;
        }
        boolean errors = run.getJobs().stream().anyMatch(job ->
                job.getStatus() == YagaRefreshJobStatus.FAILED ||
                        job.getStatus() == YagaRefreshJobStatus.RESULT_UNKNOWN
        );
        run.setStatus(errors
                ? YagaRefreshRunStatus.COMPLETED_WITH_ERRORS
                : YagaRefreshRunStatus.COMPLETED);
        run.setCompletedAt(clock.instant());
    }

    private boolean matchesOldHidden(
            HideSnapshot snapshot,
            YagaImportedProductData data
    ) {
        if (!matchesIdentity(
                snapshot.oldExternalId(),
                snapshot.oldShopSlug(),
                snapshot.oldProductSlug(),
                data
        ) || data.deletedAt() != null) {
            return false;
        }
        return "hidden".equals(data.status()) ||
                "not-visible".equals(data.status());
    }

    private boolean matchesNewPublished(
            HideSnapshot snapshot,
            YagaImportedProductData data
    ) {
        return matchesIdentity(
                snapshot.newExternalId(),
                snapshot.newShopSlug(),
                snapshot.newProductSlug(),
                data
        ) && "published".equals(data.status()) &&
                data.hiddenAt() == null && data.deletedAt() == null;
    }

    private boolean matchesIdentity(
            String externalId,
            String shopSlug,
            String productSlug,
            YagaImportedProductData data
    ) {
        return data != null && data.externalId() != null &&
                externalId.equals(data.externalId().toString()) &&
                shopSlug.equals(data.shopSlug()) &&
                productSlug.equals(data.productSlug());
    }

    private HideSnapshot snapshot(YagaRefreshJob job, ListingPair pair) {
        return new HideSnapshot(
                pair.oldListing().getExternalListingId(),
                pair.oldListing().getShopSlug(),
                pair.oldListing().getProductSlug(),
                pair.oldListing().getExternalUrl(),
                pair.newListing().getExternalListingId(),
                pair.newListing().getShopSlug(),
                pair.newListing().getProductSlug(),
                pair.newListing().getExternalUrl()
        );
    }

    private YagaRefreshHidePreparationResponse preparationResponse(
            ExistingHide existing,
            YagaHidePreparationResponse preparation,
            YagaHideReadinessResponse readiness
    ) {
        return new YagaRefreshHidePreparationResponse(
                existing.runId(), existing.jobId(), existing.productId(),
                existing.oldListingId(), existing.newListingId(),
                YagaRefreshJobStatus.HIDING_OLD,
                preparation.preparationId(),
                YagaRefreshHideStatus.AWAITING_CONFIRMATION,
                preparation.confirmationToken(), preparation.expiresAt(),
                safeReadiness(readiness)
        );
    }

    private YagaRefreshHidePreparationResponse preparationResponse(
            ExistingHide existing,
            YagaHidePreparationStatusResponse session,
            YagaHideReadinessResponse readiness
    ) {
        return new YagaRefreshHidePreparationResponse(
                existing.runId(), existing.jobId(), existing.productId(),
                existing.oldListingId(), existing.newListingId(),
                YagaRefreshJobStatus.HIDING_OLD,
                session.preparationId(),
                YagaRefreshHideStatus.AWAITING_CONFIRMATION,
                null, session.expiresAt(), safeReadiness(readiness)
        );
    }

    private YagaRefreshHideReadinessResponse safeReadiness(
            YagaHideReadinessResponse readiness
    ) {
        return new YagaRefreshHideReadinessResponse(
                readiness.preparationId(), readiness.sessionStatus(),
                readiness.targetStillValid(), readiness.candidateCount(),
                readiness.visibleCandidateCount(),
                readiness.enabledCandidateCount(),
                readiness.readyForConfirmation(), readiness.inspectedAt()
        );
    }

    private YagaRefreshHideResultResponse result(YagaRefreshJob job) {
        return new YagaRefreshHideResultResponse(
                job.getRun().getId(), job.getId(), job.getRun().getStatus(),
                job.getStatus(), job.getHidePreparationId(),
                job.getHideStatus(), job.getOldListing().getId(),
                job.getNewListing() == null ? null : job.getNewListing().getId(),
                job.getHideConfirmedAt(), job.getLastErrorCode(),
                job.getLastSafeErrorMessage()
        );
    }

    private YagaRefreshJob requireLockedJob(UUID runId, UUID jobId) {
        YagaRefreshRun run = runRepository.findForUpdateWithJobsById(runId)
                .orElseThrow(() -> new YagaRefreshRunNotFoundException(runId));
        return run.getJobs().stream()
                .filter(job -> job.getId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new YagaRefreshJobNotFoundException(jobId));
    }

    private YagaHidingSessionManager requireSessionManager() {
        YagaHidingSessionManager manager = sessionProvider.getIfAvailable();
        if (manager == null) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hiding workflow must be enabled for refresh hiding"
            );
        }
        return manager;
    }

    private YagaHidePreparationResponse prepareSession(
            YagaHidingSessionManager manager,
            Long oldListingId
    ) {
        try {
            return manager.prepare(oldListingId);
        } catch (YagaHidingAuthException exception) {
            throw new YagaRefreshHidingAuthException();
        } catch (YagaHidingPreconditionException exception) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide preparation target is invalid"
            );
        }
    }

    private YagaHideReadinessResponse inspectReadiness(
            YagaHidingSessionManager manager,
            UUID preparationId
    ) {
        try {
            return manager.readiness(preparationId);
        } catch (YagaHidingAuthException exception) {
            throw new YagaRefreshHidingAuthException();
        } catch (YagaHidingPreconditionException exception) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga hide preparation target is invalid"
            );
        }
    }

    private void safelyCancel(YagaHidingSessionManager manager, UUID id) {
        try {
            manager.cancel(id);
        } catch (RuntimeException ignored) {
            // The session is already terminal or absent.
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ExistingHide(
            UUID runId, UUID jobId, Long productId, Long oldListingId,
            Long newListingId, UUID preparationId, Instant confirmStartedAt
    ) {
    }

    private record ConfirmState(
            UUID preparationId,
            YagaRefreshHideResultResponse terminalResponse
    ) {
    }

    private record ReconcileState(
            HideSnapshot snapshot,
            UUID preparationId,
            YagaRefreshHideResultResponse terminalResponse
    ) {
    }

    private record ListingPair(
            MarketplaceListing oldListing,
            MarketplaceListing newListing
    ) {
    }

    private record HideSnapshot(
            String oldExternalId, String oldShopSlug, String oldProductSlug,
            String oldUrl, String newExternalId, String newShopSlug,
            String newProductSlug, String newUrl
    ) {
    }
}
