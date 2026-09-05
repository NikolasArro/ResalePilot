package ee.nikolas.resalepilot.workflow.yaga.refresh;

import ee.nikolas.resalepilot.workflow.yaga.refresh.controller.YagaRefreshController;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "yaga.refresh.enabled=true",
        "yaga.refresh.fixed-delay=1d"
})
@Testcontainers
class YagaRefreshEnabledContextTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("resalepilot")
                    .withUsername("resalepilot")
                    .withPassword("resalepilot");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void schedulerAndEndpointExistWhenRefreshIsEnabled() {
        assertThat(applicationContext
                .getBeansOfType(YagaRefreshScheduler.class))
                .hasSize(1);
        assertThat(applicationContext
                .getBeansOfType(YagaRefreshController.class))
                .hasSize(1);
    }
}
