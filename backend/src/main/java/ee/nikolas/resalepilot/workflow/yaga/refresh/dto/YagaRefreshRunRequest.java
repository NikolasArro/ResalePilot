package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YagaRefreshRunRequest(
        Long yagaAccountId,
        Integer batchSize,
        YagaRefreshMode mode,
        @NotBlank @Size(max = 120) String idempotencyKey
) {
    public YagaRefreshRunRequest(
            Integer batchSize,
            YagaRefreshMode mode,
            String idempotencyKey
    ) {
        this(null, batchSize, mode, idempotencyKey);
    }
}
