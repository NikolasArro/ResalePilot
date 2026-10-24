package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceLikedException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshCandidateRow;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class YagaRefreshCandidateSelector {

    private final YagaRefreshJobRepository jobRepository;
    private final Clock clock;
    private final YagaRefreshSourceValidationService sourceValidationService;

    public YagaRefreshCandidateSelector(
            YagaRefreshJobRepository jobRepository,
            Clock clock,
            YagaRefreshSourceValidationService sourceValidationService
    ) {
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.sourceValidationService = sourceValidationService;
    }

    public List<YagaRefreshCandidate> selectForUpdate(
            Long yagaAccountId,
            int limit
    ) {
        return map(jobRepository.selectCandidatesForUpdate(
                yagaAccountId,
                clock.instant().minus(Duration.ofDays(5)),
                limit
        ));
    }

    public List<YagaRefreshCandidate> selectListingForUpdate(
            Long yagaAccountId, Long listingId
    ) {
        return map(jobRepository.selectCandidatesForUpdateFiltered(
                yagaAccountId,
                clock.instant().minus(Duration.ofDays(5)),
                1,
                listingId
        ));
    }

    /** Called only for batch ON_DEMAND, inside the existing account/selection transaction. */
    public List<YagaRefreshCandidate> selectZeroLikeBatchForUpdate(Long accountId, int limit) {
        List<YagaRefreshCandidate> accepted = new ArrayList<>();
        List<Long> examined = new ArrayList<>(List.of(-1L));
        var cutoff = clock.instant().minus(Duration.ofDays(5));
        while (accepted.size() < limit) {
            var next = map(jobRepository.selectZeroLikeCandidatesForUpdate(accountId, cutoff, examined));
            if (next.isEmpty()) break;
            var candidate = next.getFirst();
            if (examined.contains(candidate.listingId())) {
                throw new IllegalStateException("Candidate selection did not advance");
            }
            examined.add(candidate.listingId());
            try {
                sourceValidationService.requireActiveZeroLikes(new YagaRefreshSourceValidationService.Source(
                        candidate.externalListingId(), candidate.shopSlug(), candidate.productSlug(), candidate.externalUrl()));
                accepted.add(candidate);
            } catch (YagaRefreshSourceLikedException | YagaRefreshSourceInactiveException rejected) {
                // Definitively ineligible: retain ordering and refill without creating a job.
            }
            // Unknown identity/count or unavailable verification aborts the selection transaction.
        }
        return List.copyOf(accepted);
    }

    private List<YagaRefreshCandidate> map(
            List<YagaRefreshCandidateRow> rows
    ) {
        return rows
                .stream()
                .map(row -> new YagaRefreshCandidate(
                        row.getListingId(),
                        row.getYagaAccountId(),
                        row.getProductId(),
                        row.getSku(),
                        row.getTitle(),
                        row.getExternalListingId(),
                        row.getShopSlug(),
                        row.getProductSlug(),
                        row.getExternalUrl(),
                        row.getExternalCreatedAt(),
                        row.getListingCreatedAt(),
                        row.getOrderingTimestamp(),
                        row.getProductImageCount(),
                        row.getListingImageCount()
                ))
                .toList();
    }
}
