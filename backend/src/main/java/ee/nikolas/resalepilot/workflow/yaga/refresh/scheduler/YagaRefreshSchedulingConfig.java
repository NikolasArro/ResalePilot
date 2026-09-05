package ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "yaga.refresh",
        name = "enabled",
        havingValue = "true"
)
public class YagaRefreshSchedulingConfig {
}
