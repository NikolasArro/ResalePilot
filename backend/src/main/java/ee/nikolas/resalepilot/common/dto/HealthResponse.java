package ee.nikolas.resalepilot.common.dto;

import java.time.Instant;

public record HealthResponse(
        String application,
        String status,
        Instant timestamp
) {
}
