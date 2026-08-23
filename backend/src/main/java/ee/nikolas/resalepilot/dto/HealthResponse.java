package ee.nikolas.resalepilot.dto;

import java.time.Instant;

public record HealthResponse(
        String application,
        String status,
        Instant timestamp
) {
}
