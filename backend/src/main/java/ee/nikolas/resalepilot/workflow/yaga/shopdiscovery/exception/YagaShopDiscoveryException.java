package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.exception;

public class YagaShopDiscoveryException extends RuntimeException {

    private final java.util.Map<String, String> details;

    public YagaShopDiscoveryException(String message) {
        super(message);
        this.details = java.util.Map.of();
    }

    public YagaShopDiscoveryException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.details = java.util.Map.of();
    }

    public YagaShopDiscoveryException(
            String message,
            Throwable cause,
            java.util.Map<String, String> details
    ) {
        super(message, cause);
        this.details = java.util.Map.copyOf(details);
    }

    public java.util.Map<String, String> getDetails() {
        return details;
    }
}
