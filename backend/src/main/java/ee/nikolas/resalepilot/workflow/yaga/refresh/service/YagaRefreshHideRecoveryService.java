package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublicProductUrlValidator;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideRecoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.*;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class YagaRefreshHideRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(YagaRefreshHideRecoveryService.class);
    private final YagaAccountService accounts;
    private final YagaRefreshRunRepository runs;
    private final YagaRefreshJobRepository jobs;
    private final MarketplaceListingRepository listings;
    private final Clock clock;

    public YagaRefreshHideRecoveryService(YagaAccountService accounts, YagaRefreshRunRepository runs,
            YagaRefreshJobRepository jobs, MarketplaceListingRepository listings, Clock clock) {
        this.accounts = accounts;
        this.runs = runs;
        this.jobs = jobs;
        this.listings = listings;
        this.clock = clock;
    }

    @Transactional
    public YagaRefreshHideRecoveryResponse recover(UUID runId, UUID jobId) {
        // Match run planning's lock order so recovery cannot race a newly selected batch.
        Long accountId = runs.findAccountIdById(runId).orElseThrow(() -> new YagaRefreshRunNotFoundException(runId));
        var account = accounts.lockForRefreshPlanning(accountId);
        var run = runs.findForUpdateWithJobsById(runId).orElseThrow(() -> new YagaRefreshRunNotFoundException(runId));
        var job = run.getJobs().stream().filter(candidate -> candidate.getId().equals(jobId)).findFirst()
                .orElseThrow(() -> new YagaRefreshJobNotFoundException(jobId));
        require(account.isEnabled() && accountId.equals(run.getYagaAccount().getId()), "Account is disabled or changed");
        boolean alreadyRecovered = run.getStatus() == YagaRefreshRunStatus.PROCESSING &&
                job.getStatus() == YagaRefreshJobStatus.NEW_LISTING_CONFIRMED;
        require(alreadyRecovered || (job.getStatus() == YagaRefreshJobStatus.FAILED &&
                (run.getStatus() == YagaRefreshRunStatus.COMPLETED_WITH_ERRORS || run.getStatus() == YagaRefreshRunStatus.FAILED)),
                "Recovery requires a failed job in an error-terminal run");
        require(run.getMode() == YagaRefreshRunMode.AUTO || run.getMode() == YagaRefreshRunMode.MANUAL,
                "Recovery requires an executable refresh run");
        require(YagaPublicationStatus.PUBLISHED.name().equals(job.getPublicationStatus()) && job.getNewListing() != null,
                "Recovery requires a confirmed replacement publication");
        require(job.getHideStatus() == YagaRefreshHideStatus.NOT_STARTED && job.getHidePreparationId() == null &&
                job.getHidePreparedAt() == null && job.getHideConfirmStartedAt() == null && job.getHideConfirmedAt() == null,
                "Hide preparation or confirmation has already started");
        YagaRefreshSequencingGuard.requireCurrentJob(run, job);
        require(!runs.existsByYagaAccountIdAndIdNotAndStatusIn(accountId, runId, List.of(
                YagaRefreshRunStatus.CREATED, YagaRefreshRunStatus.SELECTING, YagaRefreshRunStatus.PREPARING,
                YagaRefreshRunStatus.AWAITING_CONFIRMATION, YagaRefreshRunStatus.PROCESSING)),
                "Another refresh run is active for this account");
        require(!jobs.existsByRunYagaAccountIdAndProductIdAndIdNotAndStatusIn(accountId, job.getProduct().getId(), jobId,
                List.of(YagaRefreshJobStatus.SELECTED, YagaRefreshJobStatus.PUBLISHING,
                        YagaRefreshJobStatus.NEW_LISTING_CONFIRMED, YagaRefreshJobStatus.HIDING_OLD, YagaRefreshJobStatus.RESULT_UNKNOWN)),
                "Another refresh job owns this account/product");
        var oldListing = listings.findByIdForUpdate(job.getOldListing().getId())
                .orElseThrow(() -> invalid("Source listing is missing"));
        var newListing = listings.findByIdForUpdate(job.getNewListing().getId())
                .orElseThrow(() -> invalid("Replacement listing is missing"));
        validateListing(oldListing, accountId, job.getProduct().getId(), account.getShopSlug(), true);
        validateListing(newListing, accountId, job.getProduct().getId(), account.getShopSlug(), false);
        require(!oldListing.getId().equals(newListing.getId()) &&
                !oldListing.getExternalListingId().equals(newListing.getExternalListingId()), "Replacement equals source");
        require(Objects.equals(job.getOldExternalListingId(), oldListing.getExternalListingId()) &&
                Objects.equals(job.getOldShopSlug(), oldListing.getShopSlug()) &&
                Objects.equals(job.getOldProductSlug(), oldListing.getProductSlug()) &&
                Objects.equals(job.getOldPublicUrl(), oldListing.getExternalUrl()) &&
                Objects.equals(job.getNewExternalListingId(), newListing.getExternalListingId()) &&
                Objects.equals(job.getNewShopSlug(), newListing.getShopSlug()) &&
                Objects.equals(job.getNewProductSlug(), newListing.getProductSlug()) &&
                Objects.equals(job.getNewProductUrl(), newListing.getExternalUrl()), "Listing identity differs from job snapshot");
        if (!alreadyRecovered) {
            log.info("Yaga local hide recovery: runId={} jobId={} newListingId={} previousJobError={} previousRunError={}",
                    runId, jobId, newListing.getId(), job.getLastErrorCode(), run.getLastErrorCode());
            job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
            job.setCompletedAt(null);
            job.setLastErrorCode(null);
            job.setLastSafeErrorMessage(null);
            job.setUpdatedAt(clock.instant());
            run.setStatus(YagaRefreshRunStatus.PROCESSING);
            run.setCompletedAt(null);
            run.setLastErrorCode(null);
            run.setLastSafeErrorMessage(null);
        }
        return new YagaRefreshHideRecoveryResponse(runId, jobId, newListing.getId(), run.getStatus(), job.getStatus(), !alreadyRecovered);
    }

    private void validateListing(MarketplaceListing listing, Long accountId, Long productId, String shop, boolean current) {
        require(listing.getMarketplace() == Marketplace.YAGA && listing.getYagaAccount() != null &&
                accountId.equals(listing.getYagaAccount().getId()) && productId.equals(listing.getProduct().getId()) &&
                shop.equals(listing.getShopSlug()) && listing.getStatus() == MarketplaceListingStatus.PUBLISHED &&
                listing.isCurrent() == current && listing.getHiddenAt() == null && listing.getDeletedAt() == null &&
                listing.getExternalListingId() != null && !listing.getExternalListingId().isBlank() &&
                YagaPublicProductUrlValidator.isExpectedPublicProductUrl(listing.getExternalUrl(), shop, listing.getProductSlug()),
                "Listing ownership, identity or current publication state is invalid");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw invalid(message);
    }

    private static YagaRefreshInvalidStateException invalid(String message) {
        return new YagaRefreshInvalidStateException(message);
    }
}
