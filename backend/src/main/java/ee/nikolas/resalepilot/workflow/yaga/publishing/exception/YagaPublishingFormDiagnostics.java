package ee.nikolas.resalepilot.workflow.yaga.publishing.exception;

import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaOpenFormDiagnostic;

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
        String safeErrorCode,
        YagaClothingSelectionDiagnostics clothingSelection,
        YagaPriceFillDiagnostics priceFill,
        YagaOpenFormDiagnostic openFormDiagnostic,
        String draftImageSaveDiagnostic
) {
    public YagaPublishingFormDiagnostics(
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
            String safeErrorCode,
            YagaClothingSelectionDiagnostics clothingSelection,
            YagaPriceFillDiagnostics priceFill,
            YagaOpenFormDiagnostic openFormDiagnostic
    ) {
        this(
                currentUrl, pageTitle, productDescriptionPlaceholderVisible,
                categorySelectorVisible, loginElementVisible, screenshotPath,
                visiblePricePlaceholderCandidateCount, priceInputValue, expectedConditionLabel,
                actualConditionLabel, visibleListboxCount, exactCandidateCount,
                visibleExactCandidateCount, enabledVisibleExactCandidateCount, exactLabelNodeCount,
                visibleExactLabelNodeCount, resolvedSemanticContainerCount, enabledResolvedContainerCount,
                candidateRoles, semanticContainerCount, visibleSemanticContainerCount,
                enabledSemanticContainerCount, exactOptionLabelMatchCount, visibleExactOptionLabelMatchCount,
                enabledVisibleExactOptionLabelMatchCount, optionLabelExamples, operationStage,
                exceptionClass, rootCauseClass, safeErrorCode,
                clothingSelection, priceFill, openFormDiagnostic,
                null
        );
    }

    public YagaPublishingFormDiagnostics withDraftImageSaveDiagnostic(String value) {
        return new YagaPublishingFormDiagnostics(
                currentUrl, pageTitle, productDescriptionPlaceholderVisible,
                categorySelectorVisible, loginElementVisible, screenshotPath,
                visiblePricePlaceholderCandidateCount, priceInputValue, expectedConditionLabel,
                actualConditionLabel, visibleListboxCount, exactCandidateCount,
                visibleExactCandidateCount, enabledVisibleExactCandidateCount, exactLabelNodeCount,
                visibleExactLabelNodeCount, resolvedSemanticContainerCount, enabledResolvedContainerCount,
                candidateRoles, semanticContainerCount, visibleSemanticContainerCount,
                enabledSemanticContainerCount, exactOptionLabelMatchCount, visibleExactOptionLabelMatchCount,
                enabledVisibleExactOptionLabelMatchCount, optionLabelExamples, operationStage,
                exceptionClass, rootCauseClass, safeErrorCode,
                clothingSelection, priceFill, openFormDiagnostic,
                value
        );
    }

    /** Existing preparation-failure text column; no workflow or schema change. */
    public String preparationFailureSummary() {
        String open = openFormDiagnostic == null ? null : openFormDiagnostic.safeSummary();
        if (draftImageSaveDiagnostic == null) return open;
        String summary = (open == null ? "" : open + ";") + "draftImageSave={" + draftImageSaveDiagnostic + "}";
        return summary.substring(0, Math.min(16_000, summary.length()));
    }

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
                safeErrorCode, null, null, null
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
                rootCauseClass, safeErrorCode, clothingSelection, priceFill,
                openFormDiagnostic, draftImageSaveDiagnostic
        );
    }

    public YagaPublishingFormDiagnostics withClothingSelection(
            YagaClothingSelectionDiagnostics clothingSelection
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
                rootCauseClass, safeErrorCode, clothingSelection, priceFill,
                openFormDiagnostic, draftImageSaveDiagnostic
        );
    }

    public YagaPublishingFormDiagnostics withPriceFill(
            YagaPriceFillDiagnostics priceFill
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
                rootCauseClass, safeErrorCode, clothingSelection, priceFill,
                openFormDiagnostic, draftImageSaveDiagnostic
        );
    }

    public YagaPublishingFormDiagnostics withOpenFormDiagnostic(YagaOpenFormDiagnostic value) {
        return new YagaPublishingFormDiagnostics(
                currentUrl, pageTitle,
                productDescriptionPlaceholderVisible,
                categorySelectorVisible, loginElementVisible,
                screenshotPath, visiblePricePlaceholderCandidateCount,
                priceInputValue, expectedConditionLabel,
                actualConditionLabel, visibleListboxCount,
                exactCandidateCount, visibleExactCandidateCount,
                enabledVisibleExactCandidateCount, exactLabelNodeCount,
                visibleExactLabelNodeCount, resolvedSemanticContainerCount,
                enabledResolvedContainerCount, candidateRoles,
                semanticContainerCount, visibleSemanticContainerCount,
                enabledSemanticContainerCount, exactOptionLabelMatchCount,
                visibleExactOptionLabelMatchCount,
                enabledVisibleExactOptionLabelMatchCount,
                optionLabelExamples, operationStage, exceptionClass,
                rootCauseClass, safeErrorCode, clothingSelection, priceFill,
                value, draftImageSaveDiagnostic
        );
    }
}
