package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
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
        return jobRepository
                .selectCandidatesForUpdate(
                        yagaAccountId,
                        clock.instant().minus(Duration.ofDays(5)),
                        limit
                )
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
