package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import ee.nikolas.resalepilot.ResalePilotApplication;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class YagaOnDemandRefreshLauncher {

    public static void main(String[] args) throws Exception {
        YagaRefreshCliOptions options = YagaRefreshCliOptions.parse(args);

        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(ResalePilotApplication.class)
                             .web(WebApplicationType.NONE)
                             .run(args)) {
            YagaAccountService accountService =
                    context.getBean(YagaAccountService.class);
            YagaInteractiveLoginService loginService =
                    context.getBean(YagaInteractiveLoginService.class);
            YagaRefreshAutoOrchestrationService refreshService =
                    context.getBean(YagaRefreshAutoOrchestrationService.class);

            YagaAccount account = accountService.getEntity(
                    options.accountId()
            );
            if (!account.isEnabled()) {
                throw new IllegalStateException("Yaga account is disabled");
            }

            loginService.refreshAuthState(account, options.cdpUrl());

            YagaRefreshAutoRunResult result = refreshService.runOnDemand(
                    account.getId(),
                    options.count(),
                    options.idempotencyKey()
            );
            System.out.println(
                    "ON_DEMAND Yaga refresh finished: runId=" +
                            result.runId() + " status=" + result.status() +
                            " completed=" + result.completed()
            );
        }
    }
}
