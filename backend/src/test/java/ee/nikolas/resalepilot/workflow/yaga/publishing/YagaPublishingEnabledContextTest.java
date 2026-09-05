package ee.nikolas.resalepilot.workflow.yaga.publishing;

import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.automation.YagaBrowserAutomation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "yaga.publishing.enabled=true",
        "yaga.publishing.confirm-enabled=false"
})
@Testcontainers
class YagaPublishingEnabledContextTest {

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
    private ApplicationContext context;

    @Test
    void contextLoadsWithYagaPublishingEnabled() {
        assertThat(context.getBean(YagaPublishingService.class))
                .isNotNull();
        assertThat(context.getBean(YagaPublicationSessionManager.class))
                .isNotNull();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        GoogleDriveService googleDriveService() {
            return Mockito.mock(GoogleDriveService.class);
        }

        @Bean
        @Primary
        YagaBrowserAutomation yagaBrowserAutomation() {
            return Mockito.mock(YagaBrowserAutomation.class);
        }
    }
}
