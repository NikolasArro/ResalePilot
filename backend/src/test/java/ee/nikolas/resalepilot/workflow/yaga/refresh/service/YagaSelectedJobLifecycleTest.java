package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class YagaSelectedJobLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void allPersistedExecutionMarkersPreventSkipping() {
        @SuppressWarnings("unchecked")
        Consumer<YagaRefreshJob>[] evidence = new Consumer[] {
                (Consumer<YagaRefreshJob>) job -> job.setAttemptCount(1),
                (Consumer<YagaRefreshJob>) job -> job.setStartedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setPublicationPreparationId(UUID.randomUUID()),
                (Consumer<YagaRefreshJob>) job -> job.setPublicationStatus("PREPARING"),
                (Consumer<YagaRefreshJob>) job -> job.setPublicationPreparedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setPublicationConfirmStartedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setPublicationConfirmedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setNewListing(mock(MarketplaceListing.class)),
                (Consumer<YagaRefreshJob>) job -> job.setNewExternalListingId("123"),
                (Consumer<YagaRefreshJob>) job -> job.setNewShopSlug("shop"),
                (Consumer<YagaRefreshJob>) job -> job.setNewProductSlug("slug"),
                (Consumer<YagaRefreshJob>) job -> job.setNewProductUrl("url"),
                (Consumer<YagaRefreshJob>) job -> job.setHidePreparationId(UUID.randomUUID()),
                (Consumer<YagaRefreshJob>) job -> job.setHideStatus(YagaRefreshHideStatus.RESULT_UNKNOWN),
                (Consumer<YagaRefreshJob>) job -> job.setHidePreparedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setHideConfirmStartedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setHideConfirmedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setNextRetryAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setCompletedAt(NOW),
                (Consumer<YagaRefreshJob>) job -> job.setValmisActionDiagnostic("diagnostic"),
                (Consumer<YagaRefreshJob>) job -> job.setOpenFormDiagnostic("diagnostic"),
                (Consumer<YagaRefreshJob>) job -> job.setLastErrorCode("ERROR"),
                (Consumer<YagaRefreshJob>) job -> job.setLastSafeErrorMessage("error")
        };
        for (Consumer<YagaRefreshJob> marker : evidence) {
            YagaRefreshJob job = selected();
            marker.accept(job);
            assertThat(YagaSelectedJobLifecycle.finish(job, YagaRefreshJobStatus.SKIPPED, NOW))
                    .isFalse();
            assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.SELECTED);
        }
    }

    @Test
    void selectedJobCanBecomeTerminalOnlyOnce() {
        YagaRefreshJob job = selected();
        assertThat(YagaSelectedJobLifecycle.finish(job, YagaRefreshJobStatus.SKIPPED, NOW))
                .isTrue();
        assertThat(YagaSelectedJobLifecycle.finish(job, YagaRefreshJobStatus.SKIPPED, NOW))
                .isFalse();
    }

    @Test
    void sequencingIgnoresSkippedSuccessorDuringHideRecovery() {
        YagaRefreshRun run = new YagaRefreshRun(mock(YagaAccount.class),
                YagaRefreshTriggerType.ON_DEMAND, YagaRefreshRunMode.AUTO, 2,
                "key", NOW);
        YagaRefreshJob recoverable = selected();
        recoverable.setStatus(YagaRefreshJobStatus.FAILED);
        YagaRefreshJob skipped = selected();
        skipped.setSelectionOrder(1);
        skipped.setStatus(YagaRefreshJobStatus.SKIPPED);
        run.addJob(recoverable);
        run.addJob(skipped);

        YagaRefreshSequencingGuard.requireCurrentJob(run, recoverable);
        recoverable.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        YagaRefreshSequencingGuard.requireCurrentJob(run, recoverable);
        recoverable.setStatus(YagaRefreshJobStatus.COMPLETED);
        assertThat(YagaRefreshSequencingGuard.currentJob(run)).isEmpty();
    }

    private YagaRefreshJob selected() {
        return new YagaRefreshJob(mock(Product.class), mock(MarketplaceListing.class),
                "123", "shop", "slug", "url", "title", NOW, NOW,
                1, 1, 0, NOW);
    }
}
