package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import com.google.api.services.drive.Drive;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.YagaHidingSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublicationSessionManager;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublishingService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshScheduler;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class YagaOnDemandRefreshLauncherTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("resalepilot")
                    .withUsername("resalepilot")
                    .withPassword("resalepilot");

    @TempDir
    Path tempDir;

    @Test
    void launcherContextContainsOnDemandAutoOrchestrationService() {
        try (ConfigurableApplicationContext context =
                     YagaOnDemandRefreshLauncher.applicationBuilder()
                             .sources(LauncherTestConfig.class)
                             .run(YagaOnDemandRefreshLauncher.springArguments(
                                     new String[]{
                                             "--spring.datasource.url=" +
                                             postgres.getJdbcUrl(),
                                             "--spring.datasource.username=" +
                                             postgres.getUsername(),
                                             "--spring.datasource.password=" +
                                             postgres.getPassword(),
                                             "--spring.main.allow-bean-definition-overriding=true"
                                     }
                             ))) {
            assertThat(context)
                    .isInstanceOf(AnnotationConfigApplicationContext.class);
            assertThat(context.getBeansOfType(
                    YagaRefreshAutoOrchestrationService.class
            )).hasSize(1);
            assertThat(context.getBeansOfType(
                    YagaPublicationSessionManager.class
            )).hasSize(1);
            assertThat(context.getBeansOfType(
                    YagaHidingSessionManager.class
            )).hasSize(1);
            assertThat(context.getBeansOfType(
                    GoogleDriveService.class
            )).hasSize(1);
            assertThat(context.getBeansOfType(
                    YagaPublishingService.class
            )).hasSize(1);
            assertThat(context.getBeansOfType(YagaRefreshScheduler.class))
                    .isEmpty();

            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty(
                    "yaga.publishing.enabled",
                    Boolean.class
            )).isTrue();
            assertThat(context.getBean(YagaPublishingProperties.class)
                    .isEnabled()).isTrue();
            assertThat(environment.getProperty(
                    "yaga.hiding.enabled",
                    Boolean.class
            )).isTrue();
            assertThat(context.getBean(YagaHidingProperties.class)
                    .isEnabled()).isTrue();
            assertThat(environment.getProperty(
                    "google.drive.enabled",
                    Boolean.class
            )).isTrue();
            assertThat(environment.getProperty(
                    "resalepilot.yaga.refresh.scheduler.enabled",
                    Boolean.class
            )).isFalse();
        }
    }

    @Test
    void normalApplicationDefaultsRemainDisabledWithoutLauncherArguments() {
        try (ConfigurableApplicationContext context =
                     YagaOnDemandRefreshLauncher.applicationBuilder()
                             .run(
                                     "--spring.datasource.url=" +
                                             postgres.getJdbcUrl(),
                                     "--spring.datasource.username=" +
                                             postgres.getUsername(),
                                     "--spring.datasource.password=" +
                                             postgres.getPassword()
                             )) {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty(
                    "yaga.publishing.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(environment.getProperty(
                    "yaga.hiding.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(environment.getProperty(
                    "yaga.refresh.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(environment.getProperty(
                    "google.drive.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(context.getBeansOfType(
                    YagaRefreshAutoOrchestrationService.class
            )).isEmpty();
            assertThat(context.getBeansOfType(GoogleDriveService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(YagaRefreshScheduler.class))
                    .isEmpty();
        }
    }

    @Test
    void cliArgumentsParseAccountCountAndCdpUrl() {
        YagaRefreshCliOptions options = YagaRefreshCliOptions.parse(
                new String[]{
                        "--account-id", "2",
                        "--count", "5",
                        "--cdp-url", "http://127.0.0.1:9444",
                        "--idempotency-key", "test-key"
                }
        );

        assertThat(options.accountId()).isEqualTo(2L);
        assertThat(options.count()).isEqualTo(5);
        assertThat(options.cdpUrl()).isEqualTo("http://127.0.0.1:9444");
        assertThat(options.idempotencyKey()).isEqualTo("test-key");
    }

    @Test
    void springArgumentsEnableAutomationButKeepSchedulerDisabled() {
        assertThat(YagaOnDemandRefreshLauncher.springArguments(
                new String[]{"--account-id", "2"}
        )).contains(
                "--yaga.refresh.enabled=true",
                "--yaga.refresh.execution-enabled=true",
                "--yaga.refresh.hide-execution-enabled=true",
                "--yaga.publishing.enabled=true",
                "--yaga.hiding.enabled=true",
                "--google.drive.enabled=true",
                "--resalepilot.yaga.refresh.scheduler.enabled=false",
                "--account-id",
                "2"
        );
    }

    @Test
    void account2LauncherUsesAccount2AuthStateAndPreservesCount()
            throws Exception {
        YagaAccount account = account(
                2L,
                "w-a-k-a",
                tempDir.resolve("account-2-state.json")
        );
        Files.writeString(
                tempDir.resolve("account-2-state.json"),
                "{\"cookies\":[]}"
        );
        YagaAccountService accountService = mock(YagaAccountService.class);
        YagaRefreshAutoOrchestrationService refreshService =
                mock(YagaRefreshAutoOrchestrationService.class);
        YagaPublishingProperties publishingProperties =
                new YagaPublishingProperties();
        YagaRefreshAutoRunResult expected = new YagaRefreshAutoRunResult(
                UUID.randomUUID(),
                true,
                true,
                YagaRefreshRunStatus.COMPLETED,
                null
        );
        when(accountService.getEntity(2L)).thenReturn(account);
        when(refreshService.runOnDemand(2L, 5, "test-key"))
                .thenReturn(expected);

        YagaRefreshAutoRunResult result = YagaOnDemandRefreshLauncher.run(
                new YagaRefreshCliOptions(
                        2L,
                        5,
                        "http://127.0.0.1:9444",
                        "test-key"
                ),
                accountService,
                publishingProperties,
                refreshService
        );

        assertThat(result).isSameAs(expected);
        verify(refreshService).runOnDemand(2L, 5, "test-key");
    }

    @Test
    void account1StateCannotBeUsedForAccount2() throws Exception {
        Files.writeString(
                tempDir.resolve("account-1-state.json"),
                "{\"cookies\":[]}"
        );
        YagaAccount account = account(
                2L,
                "w-a-k-a",
                tempDir.resolve("account-2-state.json")
        );
        YagaAccountService accountService = mock(YagaAccountService.class);
        YagaRefreshAutoOrchestrationService refreshService =
                mock(YagaRefreshAutoOrchestrationService.class);
        when(accountService.getEntity(2L)).thenReturn(account);

        assertThatThrownBy(() -> YagaOnDemandRefreshLauncher.run(
                new YagaRefreshCliOptions(
                        2L,
                        5,
                        null,
                        "test-key"
                ),
                accountService,
                new YagaPublishingProperties(),
                refreshService
        )).isInstanceOf(YagaPublishingAuthException.class)
                .hasMessageContaining("accountId=2")
                .hasMessageContaining("w-a-k-a");

        verify(refreshService, never())
                .runOnDemand(2L, 5, "test-key");
    }

    @Test
    void missingAuthStateStopsSafelyBeforeOnDemandOrchestration() {
        YagaAccount account = account(
                2L,
                "w-a-k-a",
                tempDir.resolve("missing-account-2-state.json")
        );
        YagaAccountService accountService = mock(YagaAccountService.class);
        YagaRefreshAutoOrchestrationService refreshService =
                mock(YagaRefreshAutoOrchestrationService.class);
        when(accountService.getEntity(2L)).thenReturn(account);

        assertThatThrownBy(() -> YagaOnDemandRefreshLauncher.run(
                new YagaRefreshCliOptions(
                        2L,
                        5,
                        null,
                        "test-key"
                ),
                accountService,
                new YagaPublishingProperties(),
                refreshService
        )).isInstanceOf(YagaPublishingAuthException.class);

        verify(refreshService, never())
                .runOnDemand(2L, 5, "test-key");
    }

    @Test
    void invalidAuthStateStopsSafelyBeforeOnDemandOrchestration()
            throws Exception {
        Path authStatePath = tempDir.resolve("invalid-account-2-state.json");
        Files.writeString(authStatePath, "{not-json");
        YagaAccount account = account(2L, "w-a-k-a", authStatePath);
        YagaAccountService accountService = mock(YagaAccountService.class);
        YagaRefreshAutoOrchestrationService refreshService =
                mock(YagaRefreshAutoOrchestrationService.class);
        when(accountService.getEntity(2L)).thenReturn(account);

        assertThatThrownBy(() -> YagaOnDemandRefreshLauncher.run(
                new YagaRefreshCliOptions(
                        2L,
                        5,
                        null,
                        "test-key"
                ),
                accountService,
                new YagaPublishingProperties(),
                refreshService
        )).isInstanceOf(YagaPublishingAuthException.class)
                .hasMessageContaining("invalid")
                .hasMessageContaining("accountId=2");

        verify(refreshService, never())
                .runOnDemand(2L, 5, "test-key");
    }

    private YagaAccount account(Long id, String shopSlug, Path authStatePath) {
        YagaAccount account = new YagaAccount(
                shopSlug,
                shopSlug,
                authStatePath.toString(),
                10
        );
        account.setId(id);
        account.setEnabled(true);
        return account;
    }

    @Configuration(proxyBeanMethods = false)
    static class LauncherTestConfig {

        @Bean
        Drive googleDrive() {
            return mock(Drive.class);
        }
    }
}
