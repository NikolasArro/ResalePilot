package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshCandidateRow;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Component
public class YagaRefreshCandidateSelector {

    private final YagaRefreshJobRepository jobRepository;
    private final Clock clock;

    public YagaRefreshCandidateSelector(
            YagaRefreshJobRepository jobRepository,
            Clock clock
    ) {
        this.jobRepository = jobRepository;
        this.clock = clock;
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
