package ee.nikolas.resalepilot.workflow.yaga.refresh.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "yaga.refresh")
public record YagaRefreshProperties(
        boolean enabled,
        @NotNull YagaRefreshMode mode,
        @Min(1) int batchSize,
        @NotNull YagaRefreshScheduleType scheduleType,
        @NotNull Duration fixedDelay,
        @NotNull String cron,
        @NotNull ZoneId zone,
        @Min(1) @Max(HARD_MAX_BATCH_SIZE) int maxBatchSize
) {
    public static final int HARD_MAX_BATCH_SIZE = 100;

    public YagaRefreshProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "yaga.refresh.batch-size must be at least 1"
            );
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException(
                    "yaga.refresh.max-batch-size must be at least 1"
            );
        }
        if (maxBatchSize > HARD_MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "yaga.refresh.max-batch-size must not exceed " +
                            HARD_MAX_BATCH_SIZE
            );
        }
        if (batchSize > maxBatchSize) {
            throw new IllegalArgumentException(
                    "yaga.refresh.batch-size must not exceed yaga.refresh.max-batch-size"
            );
        }
    }
}
