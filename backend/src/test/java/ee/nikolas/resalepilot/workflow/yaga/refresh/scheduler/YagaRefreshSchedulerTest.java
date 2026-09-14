package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class YagaRefreshSchedulerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-05T03:00:00Z"),
            ZoneId.of("Europe/Tallinn")
    );

    @Test
    void scheduledEntryPointDelegatesToAutoOrchestrationService() {
        YagaRefreshAutoOrchestrationService service =
                mock(YagaRefreshAutoOrchestrationService.class);
        YagaRefreshScheduler scheduler =
                new YagaRefreshScheduler(provider(service), clock);

        scheduler.runScheduledRefresh();

        verify(service).runScheduled(startsWith("scheduled-auto-"));
    }

    @Test
    void skippedRunIsHandledWithoutThrowing(CapturedOutput output) {
        YagaRefreshAutoOrchestrationService service =
                mock(YagaRefreshAutoOrchestrationService.class);
        org.mockito.Mockito.when(service.runScheduled(startsWith("scheduled-auto-")))
                .thenReturn(YagaRefreshAutoRunResult.skipped(
                        "An active Yaga refresh run is already processing"
                ));
        YagaRefreshScheduler scheduler =
                new YagaRefreshScheduler(provider(service), clock);

        scheduler.runScheduledRefresh();

        verify(service).runScheduled(startsWith("scheduled-auto-"));
        assertThat(output).contains("Yaga AUTO refresh scheduler triggered");
        assertThat(output)
                .contains("Yaga AUTO refresh scheduler skipped: reason=An active Yaga refresh run is already processing");
    }

    @Test
    void completedRunIsHandledWithoutThrowing() {
        YagaRefreshAutoOrchestrationService service =
                mock(YagaRefreshAutoOrchestrationService.class);
        org.mockito.Mockito.when(service.runScheduled(startsWith("scheduled-auto-")))
                .thenReturn(new YagaRefreshAutoRunResult(
                        java.util.UUID.randomUUID(),
                        true,
                        true,
                        YagaRefreshRunStatus.COMPLETED,
                        null
                ));
        YagaRefreshScheduler scheduler =
                new YagaRefreshScheduler(provider(service), clock);

        scheduler.runScheduledRefresh();

        verify(service).runScheduled(startsWith("scheduled-auto-"));
    }

    @Test
    void missingExecutionServiceSkipsWithoutThrowing(CapturedOutput output) {
        YagaRefreshScheduler scheduler =
                new YagaRefreshScheduler(provider(null), clock);

        scheduler.runScheduledRefresh();

        assertThat(output).contains("Yaga AUTO refresh scheduler triggered");
        assertThat(output)
                .contains("Yaga AUTO refresh scheduler skipped: reason=disabled");
    }

    private ObjectProvider<YagaRefreshAutoOrchestrationService> provider(
            YagaRefreshAutoOrchestrationService service
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaRefreshAutoOrchestrationService> provider =
                mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable())
                .thenReturn(service);
        return provider;
    }
}
