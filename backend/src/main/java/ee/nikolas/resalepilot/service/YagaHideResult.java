package ee.nikolas.resalepilot.service;

import java.time.Instant;

public record YagaHideResult(
        boolean clickPerformed,
        String currentUrl,
        Instant clickedAt
) {
}
