package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto;

public record YagaShopDiscoveryFailedResponse(
        String productSlug,
        YagaShopDiscoveryReason reason,
        String safeMessage
) {
}
