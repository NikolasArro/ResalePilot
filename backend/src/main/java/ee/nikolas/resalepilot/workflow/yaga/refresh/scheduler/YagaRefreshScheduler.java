package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        prefix = "resalepilot.yaga.refresh.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class YagaRefreshScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(YagaRefreshScheduler.class);

    private final ObjectProvider<YagaRefreshAutoOrchestrationService>
            orchestrationServiceProvider;
    private final Clock clock;

    public YagaRefreshScheduler(
            ObjectProvider<YagaRefreshAutoOrchestrationService>
                    orchestrationServiceProvider,
            Clock clock
    ) {
        this.orchestrationServiceProvider = orchestrationServiceProvider;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${resalepilot.yaga.refresh.scheduler.cron:0 0 3 * * *}",
            zone = "${resalepilot.yaga.refresh.scheduler.zone:Europe/Tallinn}"
    )
    public void runScheduledRefresh() {
        log.info("Yaga AUTO refresh scheduler triggered");
        YagaRefreshAutoOrchestrationService orchestrationService =
                orchestrationServiceProvider.getIfAvailable();
        if (orchestrationService == null) {
            log.info(
                    "Yaga AUTO refresh scheduler skipped: reason={}",
                    "disabled"
            );
            return;
        }
        String idempotencyKey = "scheduled-auto-" + clock.instant();
        try {
            YagaRefreshAutoRunResult result =
                    orchestrationService.runScheduled(idempotencyKey);
            if (result != null && !result.started()) {
                log.info(
                        "Yaga AUTO refresh scheduler skipped: reason={}",
                        result.safeMessage()
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Yaga AUTO refresh scheduler failed safely: exceptionClass={}",
                    exception.getClass().getName()
            );
        }
    }
}
