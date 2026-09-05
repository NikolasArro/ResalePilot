package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YagaRefreshRunRequest(
        Integer batchSize,
        YagaRefreshMode mode,
        @NotBlank @Size(max = 120) String idempotencyKey
) {
}
