package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto;

import java.util.List;

public record YagaShopDiscoveryResponse(
        String shopSlug,
        int pagesVisited,
        int cardsDiscovered,
        int uniqueCandidates,
        int activePublishedCount,
        int activeNewCount,
        int activeExistingCount,
        int skippedCount,
        int failedCount,
        boolean truncated,
        List<YagaShopDiscoveredListingResponse> activeNew,
        List<YagaShopDiscoveredListingResponse> activeExisting,
        List<YagaShopDiscoverySkippedResponse> skipped,
        List<YagaShopDiscoveryFailedResponse> failed
) {
}
