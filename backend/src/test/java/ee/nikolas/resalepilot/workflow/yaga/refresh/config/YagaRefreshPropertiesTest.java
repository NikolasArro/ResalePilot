package ee.nikolas.resalepilot.workflow.yaga.refresh.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaRefreshPropertiesTest {

    @Test
    void batchSizeCannotExceedMaxBatchSize() {
        assertThatThrownBy(() ->
                properties(11, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void maxBatchSizeCannotExceedHardSafetyLimit() {
        assertThatThrownBy(() ->
                properties(10, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private YagaRefreshProperties properties(
            int batchSize,
            int maxBatchSize
    ) {
        return new YagaRefreshProperties(
                false,
                YagaRefreshMode.DRY_RUN,
                batchSize,
                YagaRefreshScheduleType.FIXED_DELAY,
                Duration.ofDays(1),
                "0 0 3 * * *",
                ZoneId.of("Europe/Tallinn"),
                maxBatchSize
        );
    }
}
