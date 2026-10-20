package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;

import java.time.Instant;

/** Keeps a selected job terminal only when it has no persisted execution evidence. */
final class YagaSelectedJobLifecycle {
    private YagaSelectedJobLifecycle() { }

    static boolean unexecuted(YagaRefreshJob job) {
        return job.getStatus() == YagaRefreshJobStatus.SELECTED
                && job.getAttemptCount() == 0
                && job.getStartedAt() == null
                && job.getPublicationPreparationId() == null
                && job.getPublicationStatus() == null
                && job.getPublicationPreparedAt() == null
                && job.getPublicationConfirmStartedAt() == null
                && job.getPublicationConfirmedAt() == null
                && job.getNewListing() == null
                && job.getNewExternalListingId() == null
                && job.getNewShopSlug() == null
                && job.getNewProductSlug() == null
                && job.getNewProductUrl() == null
                && job.getHidePreparationId() == null
                && job.getHideStatus() == YagaRefreshHideStatus.NOT_STARTED
                && job.getHidePreparedAt() == null
                && job.getHideConfirmStartedAt() == null
                && job.getHideConfirmedAt() == null
                && job.getNextRetryAt() == null
                && job.getCompletedAt() == null
                && job.getValmisActionDiagnostic() == null
                && job.getOpenFormDiagnostic() == null
                && job.getLastErrorCode() == null
                && job.getLastSafeErrorMessage() == null;
    }

    static boolean finish(YagaRefreshJob job, YagaRefreshJobStatus status, Instant now) {
        if (!unexecuted(job)) {
            return false;
        }
        if (status != YagaRefreshJobStatus.SKIPPED
                && status != YagaRefreshJobStatus.DRY_RUN_COMPLETED) {
            throw new IllegalArgumentException("Unsupported selected-job terminal status");
        }
        job.setStatus(status);
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        return true;
    }
}
