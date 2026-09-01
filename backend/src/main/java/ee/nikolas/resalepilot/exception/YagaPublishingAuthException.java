package ee.nikolas.resalepilot.exception;

public class YagaPublishingAuthException extends RuntimeException {

    private final YagaPublishingFormDiagnostics diagnostics;

    public YagaPublishingAuthException(String message) {
        super(message);
        this.diagnostics = null;
    }

    public YagaPublishingAuthException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.diagnostics = null;
    }

    public YagaPublishingAuthException(
            String message,
            YagaPublishingFormDiagnostics diagnostics
    ) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public YagaPublishingAuthException(
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
