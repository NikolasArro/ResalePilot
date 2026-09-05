package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

import java.time.Instant;

public record YagaHideResult(
        boolean clickPerformed,
        String currentUrl,
        Instant clickedAt
) {
}
