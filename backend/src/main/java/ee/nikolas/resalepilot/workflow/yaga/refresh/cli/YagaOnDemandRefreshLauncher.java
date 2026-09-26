package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import ee.nikolas.resalepilot.ResalePilotApplication;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountAuthStateResolver;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class YagaOnDemandRefreshLauncher {

    private static final String[] ON_DEMAND_SPRING_ARGS = {
            "--yaga.refresh.enabled=true",
            "--yaga.refresh.execution-enabled=true",
            "--yaga.refresh.hide-execution-enabled=true",
            "--yaga.publishing.enabled=true",
            "--yaga.hiding.enabled=true",
            "--google.drive.enabled=true",
            "--resalepilot.yaga.refresh.scheduler.enabled=false"
    };

    public static void main(String[] args) throws Exception {
        YagaRefreshCliOptions options = YagaRefreshCliOptions.parse(args);

        try (ConfigurableApplicationContext context =
                     applicationBuilder().run(springArguments(args))) {
            YagaAccountService accountService =
                    context.getBean(YagaAccountService.class);
            YagaPublishingProperties publishingProperties =
                    context.getBean(YagaPublishingProperties.class);
            YagaRefreshAutoOrchestrationService refreshService =
                    context.getBean(YagaRefreshAutoOrchestrationService.class);

            YagaRefreshAutoRunResult result = run(
                    options,
                    accountService,
                    publishingProperties,
                    refreshService
            );
            System.out.println(
                    "ON_DEMAND Yaga refresh finished: runId=" +
                            result.runId() + " status=" + result.status() +
                            " completed=" + result.completed()
            );
        } catch (YagaPublishingAuthException exception) {
            System.err.println(
                    "Yaga auth state is missing or invalid for accountId=" +
                            options.accountId() +
                            ". Run YagaAuthSetup for this account before " +
                            "ON_DEMAND refresh."
            );
            throw exception;
        }
    }

    static YagaRefreshAutoRunResult run(
            YagaRefreshCliOptions options,
            YagaAccountService accountService,
            YagaPublishingProperties publishingProperties,
            YagaRefreshAutoOrchestrationService refreshService
    ) {
        YagaAccount account = accountService.getEntity(options.accountId());
        if (!account.isEnabled()) {
            throw new IllegalStateException("Yaga account is disabled");
        }

        requireExistingAuthState(account, publishingProperties);
        return refreshService.runOnDemand(
                account.getId(),
                options.count(),
                options.idempotencyKey()
        );
    }

    static Path requireExistingAuthState(
            YagaAccount account,
            YagaPublishingProperties publishingProperties
    ) {
        Path authStatePath = YagaAccountAuthStateResolver.resolve(
                account,
                publishingProperties.getAuthStatePath()
        );
        if (!Files.isRegularFile(authStatePath)) {
            throw new YagaPublishingAuthException(
                    "Yaga auth state file is missing for accountId=" +
                            account.getId() + " shopSlug=" +
                            account.getShopSlug()
            );
        }
        try {
            JsonParserFactory.getJsonParser()
                    .parseMap(Files.readString(authStatePath));
        } catch (Exception exception) {
            throw new YagaPublishingAuthException(
                    "Yaga auth state file is invalid for accountId=" +
                            account.getId() + " shopSlug=" +
                            account.getShopSlug(),
                    exception
            );
        }
        return authStatePath;
    }

    static SpringApplicationBuilder applicationBuilder() {
        return new SpringApplicationBuilder(ResalePilotApplication.class)
                .web(WebApplicationType.NONE);
    }

    static String[] springArguments(String[] args) {
        String[] springArgs = Arrays.copyOf(
                ON_DEMAND_SPRING_ARGS,
                ON_DEMAND_SPRING_ARGS.length + args.length
        );
        System.arraycopy(
                args,
                0,
                springArgs,
                ON_DEMAND_SPRING_ARGS.length,
                args.length
        );
        return springArgs;
    }
}
