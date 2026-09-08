package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshJobRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class YagaRefreshCandidateSelector {

    private final YagaRefreshJobRepository jobRepository;

    public YagaRefreshCandidateSelector(
            YagaRefreshJobRepository jobRepository
    ) {
        this.jobRepository = jobRepository;
    }

    public List<YagaRefreshCandidate> selectForUpdate(int limit) {
        return jobRepository.selectCandidatesForUpdate(limit)
                .stream()
                .map(row -> new YagaRefreshCandidate(
                        row.getListingId(),
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
