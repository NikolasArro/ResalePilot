package ee.nikolas.resalepilot.workflow.yaga.publishing.exception;

import java.nio.file.Path;
import java.util.List;

public record YagaPublishingFormDiagnostics(
        String currentUrl,
        String pageTitle,
        boolean productDescriptionPlaceholderVisible,
        boolean categorySelectorVisible,
        boolean loginElementVisible,
        Path screenshotPath,
        int visiblePricePlaceholderCandidateCount,
        String priceInputValue,
        String expectedConditionLabel,
        String actualConditionLabel,
        int visibleListboxCount,
        int exactCandidateCount,
        int visibleExactCandidateCount,
        int enabledVisibleExactCandidateCount,
        int exactLabelNodeCount,
        int visibleExactLabelNodeCount,
        int resolvedSemanticContainerCount,
        int enabledResolvedContainerCount,
        List<String> candidateRoles,
        int semanticContainerCount,
        int visibleSemanticContainerCount,
        int enabledSemanticContainerCount,
        int exactOptionLabelMatchCount,
        int visibleExactOptionLabelMatchCount,
        int enabledVisibleExactOptionLabelMatchCount,
        List<String> optionLabelExamples,
        String operationStage,
        String exceptionClass,
        String rootCauseClass,
        String safeErrorCode
) {
    public static YagaPublishingFormDiagnostics failureMetadata(
            String operationStage,
            String exceptionClass,
            String rootCauseClass,
            String safeErrorCode
    ) {
        return new YagaPublishingFormDiagnostics(
                null, null, false, false, false, null, 0,
                null, null, null, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), 0, 0, 0, 0, 0, 0, List.of(),
                operationStage, exceptionClass, rootCauseClass,
                safeErrorCode
        );
    }

    public YagaPublishingFormDiagnostics withFailureMetadata(
            String operationStage,
            String exceptionClass,
            String rootCauseClass,
            String safeErrorCode
    ) {
        return new YagaPublishingFormDiagnostics(
                currentUrl, pageTitle,
                productDescriptionPlaceholderVisible,
                categorySelectorVisible, loginElementVisible,
                screenshotPath, visiblePricePlaceholderCandidateCount,
                priceInputValue, expectedConditionLabel,
                actualConditionLabel, visibleListboxCount,
                exactCandidateCount, visibleExactCandidateCount,
                enabledVisibleExactCandidateCount, exactLabelNodeCount,
                visibleExactLabelNodeCount,
                resolvedSemanticContainerCount,
                enabledResolvedContainerCount, candidateRoles,
                semanticContainerCount, visibleSemanticContainerCount,
                enabledSemanticContainerCount,
                exactOptionLabelMatchCount,
                visibleExactOptionLabelMatchCount,
                enabledVisibleExactOptionLabelMatchCount,
                optionLabelExamples, operationStage, exceptionClass,
                rootCauseClass, safeErrorCode
        );
    }
}
