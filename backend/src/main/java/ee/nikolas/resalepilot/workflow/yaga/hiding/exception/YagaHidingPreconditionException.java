package ee.nikolas.resalepilot.workflow.yaga.hiding.exception;

import java.util.Map;

public class YagaHidingPreconditionException
        extends RuntimeException {

    private final Map<String, String> details;

    public YagaHidingPreconditionException(String message) {
        super(message);
        this.details = Map.of();
    }

    public YagaHidingPreconditionException(
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
