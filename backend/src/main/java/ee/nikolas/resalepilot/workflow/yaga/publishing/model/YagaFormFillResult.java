package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

public record YagaFormFillResult(
        int imageCount,
        boolean descriptionFilled,
        List<String> categoryPath,
        String conditionLabel,
        BigDecimal price,
        Path screenshotPath
) {
}
