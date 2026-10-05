package ee.nikolas.resalepilot.workflow.yaga.publishing.exception;

import java.util.List;

public record YagaClothingSelectionDiagnostics(
        String field,
        List<String> requestedValues,
        boolean controlFound,
        boolean controlVisible,
        boolean controlEnabled,
        Integer exactOptionMatchCount,
        List<String> selectedValuesObserved,
        List<String> visibleOptionLabelExamples
) {
}
