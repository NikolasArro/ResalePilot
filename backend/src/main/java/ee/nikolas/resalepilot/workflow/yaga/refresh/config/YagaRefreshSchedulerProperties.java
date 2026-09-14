package ee.nikolas.resalepilot.workflow.yaga.refresh.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

@Validated
@ConfigurationProperties(
        prefix = "resalepilot.yaga.refresh.scheduler"
)
public record YagaRefreshSchedulerProperties(
        boolean enabled,
        @NotBlank String cron,
        @NotNull ZoneId zone,
        @Min(1) @Max(YagaRefreshProperties.HARD_MAX_BATCH_SIZE) int batchSize
) {
    public YagaRefreshSchedulerProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "resalepilot.yaga.refresh.scheduler.batch-size must be at least 1"
            );
        }
        if (batchSize > YagaRefreshProperties.HARD_MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "resalepilot.yaga.refresh.scheduler.batch-size must not exceed " +
                            YagaRefreshProperties.HARD_MAX_BATCH_SIZE
            );
        }
    }
}
