package ee.nikolas.resalepilot.exception;

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
        List<String> pricePlaceholderCandidateOuterHtml,
        String priceInputValue
) {
}
