package ee.nikolas.resalepilot.workflow.yaga.refresh;

import ee.nikolas.resalepilot.workflow.yaga.refresh.controller.YagaRefreshHidingExecutionController;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshHidingExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class YagaRefreshHidingExecutionDisabledContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            YagaRefreshHidingExecutionService.class,
                            YagaRefreshHidingExecutionController.class
                    )
                    .withPropertyValues(
                            "yaga.refresh.enabled=true",
                            "yaga.refresh.execution-enabled=true",
                            "yaga.refresh.hide-execution-enabled=false",
                            "yaga.hiding.enabled=true"
                    );

    @Test
    void hideExecutionBeansAreAbsentWhenDedicatedFlagIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    YagaRefreshHidingExecutionService.class
            );
            assertThat(context).doesNotHaveBean(
                    YagaRefreshHidingExecutionController.class
            );
        });
    }
}
