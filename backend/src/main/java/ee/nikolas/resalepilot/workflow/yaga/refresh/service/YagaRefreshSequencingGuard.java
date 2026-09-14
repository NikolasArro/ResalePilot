package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;

import java.util.Comparator;
import java.util.Optional;

final class YagaRefreshSequencingGuard {

    static final Comparator<YagaRefreshJob> JOB_ORDER =
            Comparator.comparingInt(YagaRefreshJob::getSelectionOrder)
                    .thenComparing(YagaRefreshJob::getId);

    private YagaRefreshSequencingGuard() {
    }

    static Optional<YagaRefreshJob> currentJob(YagaRefreshRun run) {
        return run.getJobs().stream()
                .filter(job -> job.getStatus() != YagaRefreshJobStatus.COMPLETED)
                .min(JOB_ORDER);
    }

    static void requireCurrentJob(YagaRefreshRun run, YagaRefreshJob target) {
        YagaRefreshJob current = currentJob(run).orElseThrow(() ->
                new YagaRefreshInvalidStateException(
                        "Yaga refresh run has no unfinished job"
                ));
        if (!current.getId().equals(target.getId())) {
            throw new YagaRefreshInvalidStateException(
                    "Previous refresh job is not completed"
            );
        }

        boolean anotherActiveJob = run.getJobs().stream()
                .filter(job -> !job.getId().equals(target.getId()))
                .anyMatch(YagaRefreshSequencingGuard::isActive);
        if (anotherActiveJob) {
            throw new YagaRefreshInvalidStateException(
                    "Another refresh job in this run is active"
            );
        }
    }

    private static boolean isActive(YagaRefreshJob job) {
        return job.getStatus() == YagaRefreshJobStatus.PUBLISHING ||
                job.getStatus() == YagaRefreshJobStatus.NEW_LISTING_CONFIRMED ||
                job.getStatus() == YagaRefreshJobStatus.HIDING_OLD ||
                job.getStatus() == YagaRefreshJobStatus.RESULT_UNKNOWN;
    }
}
