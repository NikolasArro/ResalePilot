package ee.nikolas.resalepilot.workflow.yaga.refresh;

import ee.nikolas.resalepilot.workflow.yaga.refresh.controller.YagaRefreshOperatorController;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshOperatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class YagaRefreshOperatorDisabledContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            YagaRefreshOperatorService.class,
                            YagaRefreshOperatorController.class
                    )
                    .withPropertyValues(
                            "yaga.refresh.enabled=true",
                            "yaga.refresh.execution-enabled=true",
                            "yaga.refresh.operator-enabled=false"
                    );

    @Test
    void operatorBeansAreAbsentWhenDedicatedFlagIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    YagaRefreshOperatorService.class
            );
            assertThat(context).doesNotHaveBean(
                    YagaRefreshOperatorController.class
            );
        });
    }
}
