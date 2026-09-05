package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshScheduleType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@ConditionalOnProperty(
        prefix = "yaga.refresh",
        name = "enabled",
        havingValue = "true"
)
public class YagaRefreshScheduler implements SchedulingConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(YagaRefreshScheduler.class);

    private final YagaRefreshProperties properties;
    private final YagaRefreshRunService refreshRunService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Instant> plannedFireTime =
            new AtomicReference<>();

    public YagaRefreshScheduler(
            YagaRefreshProperties properties,
            YagaRefreshRunService refreshRunService,
            Clock clock
    ) {
        this.properties = properties;
        this.refreshRunService = refreshRunService;
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(new ScheduledThreadPoolExecutor(1));
        taskRegistrar.addTriggerTask(this::runIfIdle, trigger());
    }

    Trigger trigger() {
        if (properties.scheduleType() == YagaRefreshScheduleType.CRON) {
            CronTrigger cronTrigger = new CronTrigger(
                    properties.cron(),
                    properties.zone()
            );
            return context -> {
                Instant next = cronTrigger.nextExecution(context);
                plannedFireTime.set(next);
                return next;
            };
        }

        return new FixedDelayTrigger(properties.fixedDelay(), clock);
    }

    YagaRefreshScheduleType configuredScheduleType() {
        return properties.scheduleType();
    }

    void runIfIdle() {
        if (!running.compareAndSet(false, true)) {
            log.info("Skipping Yaga refresh run because previous run is active");
            return;
        }

        try {
            Instant fireTime = plannedFireTime.get();
            if (fireTime == null) {
                fireTime = clock.instant();
            }
            String idempotencyKey = "scheduled-" + fireTime;
            refreshRunService.startScheduledDryRun(idempotencyKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "Scheduled Yaga refresh DRY_RUN failed: {}",
                    exception.getMessage()
            );
        } finally {
            running.set(false);
        }
    }

    boolean isRunning() {
        return running.get();
    }

    private final class FixedDelayTrigger implements Trigger {

        private final Duration fixedDelay;
        private final Clock clock;

        private FixedDelayTrigger(Duration fixedDelay, Clock clock) {
            this.fixedDelay = fixedDelay;
            this.clock = clock;
        }

        @Override
        public Instant nextExecution(TriggerContext triggerContext) {
            Instant lastCompletion = triggerContext.lastCompletion();
            Instant next = lastCompletion == null
                    ? clock.instant().plus(fixedDelay)
                    : lastCompletion.plus(fixedDelay);
            plannedFireTime.set(next);
            return next;
        }
    }
}
