package ee.nikolas.resalepilot.service;

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
