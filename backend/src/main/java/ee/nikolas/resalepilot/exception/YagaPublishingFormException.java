package ee.nikolas.resalepilot.exception;

public class YagaPublishingFormException extends RuntimeException {

    private final YagaPublishingFormDiagnostics diagnostics;

    public YagaPublishingFormException(String message) {
        super(message);
        this.diagnostics = null;
    }

    public YagaPublishingFormException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.diagnostics = null;
    }

    public YagaPublishingFormException(
            String message,
            YagaPublishingFormDiagnostics diagnostics
    ) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public YagaPublishingFormException(
            String message,
            YagaPublishingFormDiagnostics diagnostics,
            Throwable cause
    ) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }

    public YagaPublishingFormDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
