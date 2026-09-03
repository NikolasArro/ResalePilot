package ee.nikolas.resalepilot.exception;

import java.util.Map;

public class YagaPublicationReconciliationConflictException
        extends RuntimeException {

    private final Map<String, String> details;

    public YagaPublicationReconciliationConflictException(
            String message,
            Map<String, String> details
    ) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
