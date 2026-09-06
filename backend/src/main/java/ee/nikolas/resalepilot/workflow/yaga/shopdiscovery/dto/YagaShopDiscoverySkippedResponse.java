package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto;

public record YagaShopDiscoverySkippedResponse(
        String productSlug,
        YagaShopDiscoveryReason reason
) {
}
