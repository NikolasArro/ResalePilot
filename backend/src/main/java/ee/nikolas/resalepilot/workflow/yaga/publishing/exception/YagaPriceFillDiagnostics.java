package ee.nikolas.resalepilot.workflow.yaga.publishing.exception;

public record YagaPriceFillDiagnostics(
        String step,
        boolean controlFound,
        boolean controlVisible,
        boolean controlEnabled,
        boolean controlEditable,
        String controlIdentity,
        String valueBeforeFill,
        String requestedPrice,
        boolean fillAttempted,
        String valueImmediatelyAfterFill,
        String valueObservedAfterFill,
        int visibleListboxCountBefore,
        int visibleOverlayCountBefore
) {
}
