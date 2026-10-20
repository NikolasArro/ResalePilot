package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairPreview;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairCandidate;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaStrandedJobRepairResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class YagaStrandedJobRepairService {
    private final YagaAccountService accounts;
    private final YagaRefreshRunRepository runs;
    private final YagaRefreshJobRepository jobs;
    private final Clock clock;

    public YagaStrandedJobRepairService(YagaAccountService accounts,
            YagaRefreshRunRepository runs, YagaRefreshJobRepository jobs, Clock clock) {
        this.accounts = accounts;
        this.runs = runs;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public YagaStrandedJobRepairPreview preview(Long accountId) {
        accounts.getEntity(accountId);
        return plan(accountId).preview(accountId);
    }

    @Transactional
    public YagaStrandedJobRepairResult apply(Long accountId,
            YagaStrandedJobRepairRequest request) {
        accounts.lockForRefreshPlanning(accountId);
        if (request == null || request.expectedEligibleJobIds() == null) {
            throw new YagaRefreshInvalidStateException("Repair preview job IDs are required");
        }
        Plan plan = plan(accountId);
        if (request.expectedEligibleJobIds().stream().anyMatch(id -> id == null)) {
            throw new YagaRefreshInvalidStateException("Repair preview job IDs are invalid");
        }
        List<UUID> expected = request.expectedEligibleJobIds().stream()
                .sorted().toList();
        if (expected.size() != expected.stream().distinct().count()
                || !expected.equals(plan.jobIds())) {
            if (plan.jobIds().isEmpty() && alreadyApplied(accountId, expected)) {
                return new YagaStrandedJobRepairResult(accountId, 0, 0);
            }
            throw new YagaRefreshInvalidStateException(
                    "Repair preview differs from current eligible jobs; request a new preview");
        }
        Instant now = clock.instant();
        int auto = 0;
        int dry = 0;
        for (Eligible eligible : plan.eligible()) {
            YagaRefreshRun run = runs.findForUpdateWithJobsById(eligible.runId())
                    .orElseThrow();
            YagaRefreshJob job = run.getJobs().stream()
                    .filter(candidate -> candidate.getId().equals(eligible.jobId()))
                    .findFirst().orElseThrow();
            if (eligibleStatus(run, job) != eligible.target()
                    || !YagaSelectedJobLifecycle.finish(job, eligible.target(), now)) {
                throw new YagaRefreshInvalidStateException(
                        "Repair eligibility changed; request a new preview");
            }
            if (eligible.target() == YagaRefreshJobStatus.SKIPPED) auto++;
            else dry++;
        }
        return new YagaStrandedJobRepairResult(accountId, auto, dry);
    }

    private boolean alreadyApplied(Long accountId, List<UUID> expected) {
        List<YagaRefreshJob> previous = jobs.findAllById(expected);
        return previous.size() == expected.size() && previous.stream().allMatch(job ->
                accountId.equals(job.getRun().getYagaAccount().getId())
                && ((job.getRun().getMode() == YagaRefreshRunMode.AUTO
                        && job.getStatus() == YagaRefreshJobStatus.SKIPPED)
                    || (job.getRun().getMode() == YagaRefreshRunMode.DRY_RUN
                        && job.getStatus() == YagaRefreshJobStatus.DRY_RUN_COMPLETED)));
    }

    private Plan plan(Long accountId) {
        List<Eligible> eligible = new ArrayList<>();
        int refused = 0;
        for (UUID runId : runs.findRunIdsWithSelectedJobs(accountId)) {
            YagaRefreshRun run = runs.findWithJobsById(runId).orElseThrow();
            for (YagaRefreshJob job : run.getJobs()) {
                if (job.getStatus() != YagaRefreshJobStatus.SELECTED) continue;
                YagaRefreshJobStatus target = eligibleStatus(run, job);
                if (target == null) refused++;
                else eligible.add(new Eligible(runId, job.getId(),
                        job.getOldListing().getId(), target));
            }
        }
        eligible.sort(Comparator.comparing(Eligible::runId).thenComparing(Eligible::jobId));
        return new Plan(eligible, refused);
    }

    private YagaRefreshJobStatus eligibleStatus(YagaRefreshRun run, YagaRefreshJob job) {
        if (!YagaSelectedJobLifecycle.unexecuted(job)) return null;
        if (run.getMode() == YagaRefreshRunMode.DRY_RUN
                && run.getStatus() == YagaRefreshRunStatus.DRY_RUN_COMPLETED) {
            return YagaRefreshJobStatus.DRY_RUN_COMPLETED;
        }
        if (run.getMode() == YagaRefreshRunMode.AUTO
                && (run.getStatus() == YagaRefreshRunStatus.COMPLETED_WITH_ERRORS
                    || run.getStatus() == YagaRefreshRunStatus.FAILED)
                && run.getJobs().stream().anyMatch(previous ->
                    previous.getSelectionOrder() < job.getSelectionOrder()
                    && (previous.getStatus() == YagaRefreshJobStatus.FAILED
                        || previous.getStatus() == YagaRefreshJobStatus.RESULT_UNKNOWN))) {
            return YagaRefreshJobStatus.SKIPPED;
        }
        return null;
    }

    private record Eligible(UUID runId, UUID jobId, Long sourceListingId,
            YagaRefreshJobStatus target) { }

    private record Plan(List<Eligible> eligible, int refused) {
        List<UUID> jobIds() {
            return eligible.stream().map(Eligible::jobId).sorted().toList();
        }
        YagaStrandedJobRepairPreview preview(Long accountId) {
            return new YagaStrandedJobRepairPreview(accountId,
                    (int) eligible.stream().filter(item -> item.target() == YagaRefreshJobStatus.SKIPPED).count(),
                    (int) eligible.stream().filter(item -> item.target() == YagaRefreshJobStatus.DRY_RUN_COMPLETED).count(),
                    refused, jobIds(), eligible.stream().map(item ->
                            new YagaStrandedJobRepairCandidate(item.runId(), item.jobId(),
                                    item.sourceListingId(), item.target())).toList());
        }
    }
}
