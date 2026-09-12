package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshScheduleType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshRunService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class YagaRefreshSchedulerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-05T03:00:00Z"),
            ZoneId.of("Europe/Tallinn")
    );

    @Test
    void fixedDelayConfigurationCreatesOnlyFixedDelayTrigger() {
        YagaRefreshScheduler scheduler = scheduler(
                YagaRefreshScheduleType.FIXED_DELAY,
                mock(YagaRefreshRunService.class)
        );

        assertThat(scheduler.configuredScheduleType())
                .isEqualTo(YagaRefreshScheduleType.FIXED_DELAY);
    }

    @Test
    void cronConfigurationCreatesOnlyCronTrigger() {
        YagaRefreshScheduler scheduler = scheduler(
                YagaRefreshScheduleType.CRON,
                mock(YagaRefreshRunService.class)
        );

        assertThat(scheduler.configuredScheduleType())
                .isEqualTo(YagaRefreshScheduleType.CRON);
    }

    @Test
    void schedulerCreatesOnlyDryRunThroughService() {
        YagaRefreshRunService service = mock(YagaRefreshRunService.class);
        YagaRefreshScheduler scheduler = scheduler(
                YagaRefreshScheduleType.FIXED_DELAY,
                service
        );

        scheduler.runIfIdle();

        verify(service).startScheduledDryRun(startsWith("scheduled-"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void overlappingScheduledRunIsSkipped() throws Exception {
        YagaRefreshRunService service = mock(YagaRefreshRunService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).startScheduledDryRun(startsWith("scheduled-"));
        YagaRefreshScheduler scheduler = scheduler(
                YagaRefreshScheduleType.FIXED_DELAY,
                service
        );
        var executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(scheduler::runIfIdle);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            scheduler.runIfIdle();

            verify(service, timeout(1000).times(1))
                    .startScheduledDryRun(startsWith("scheduled-"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private YagaRefreshScheduler scheduler(
            YagaRefreshScheduleType scheduleType,
            YagaRefreshRunService service
    ) {
        return new YagaRefreshScheduler(
                new YagaRefreshProperties(
                        true,
                        false,
                        YagaRefreshMode.DRY_RUN,
                        10,
                        scheduleType,
                        Duration.ofDays(1),
                        "0 0 3 * * *",
                        ZoneId.of("Europe/Tallinn"),
                        50
                ),
                service,
                clock
        );
    }
}
