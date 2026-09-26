package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import ee.nikolas.resalepilot.marketplace.exception.YagaAccountNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshScheduler;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshRunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class YagaOnDemandRefreshControllerTest {

    @Mock
    private YagaAccountService accountService;

    @Mock
    private YagaRefreshAutoOrchestrationService orchestrationService;

    @Mock
    private YagaRefreshRunService runService;

    @Mock
    private YagaRefreshScheduler scheduler;

    @Test
    void account2CountOneInvokesRunOnDemand() throws Exception {
        UUID runId = UUID.randomUUID();
        when(accountService.getEntity(2L))
                .thenReturn(account(2L, "w-a-k-a", true));
        when(orchestrationService.runOnDemand(2L, 1, "smoke-key"))
                .thenReturn(new YagaRefreshAutoRunResult(
                        runId,
                        true,
                        true,
                        YagaRefreshRunStatus.COMPLETED,
                        null
                ));
        when(runService.getRun(runId)).thenReturn(runResponse(runId, 1));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/refresh-on-demand" +
                                "?count=1&idempotencyKey=smoke-key",
                        2L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.selectedJobCount").value(1));

        verify(orchestrationService).runOnDemand(2L, 1, "smoke-key");
        verify(runService).getRun(runId);
        verifyNoInteractions(scheduler);
    }

    @Test
    void invalidCountRejected() throws Exception {
        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/refresh-on-demand" +
                                "?count=0",
                        2L
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("count must be at least 1"));

        verifyNoInteractions(accountService);
        verifyNoInteractions(orchestrationService);
        verifyNoInteractions(runService);
        verifyNoInteractions(scheduler);
    }

    @Test
    void unknownAccountRejected() throws Exception {
        when(accountService.getEntity(404L))
                .thenThrow(new YagaAccountNotFoundException(404L));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/refresh-on-demand" +
                                "?count=1",
                        404L
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Yaga account not found with id: 404"));

        verify(orchestrationService, never())
                .runOnDemand(anyLong(), anyInt(), anyString());
        verifyNoInteractions(runService);
        verifyNoInteractions(scheduler);
    }

    @Test
    void disabledAccountRejected() throws Exception {
        when(accountService.getEntity(2L))
                .thenReturn(account(2L, "w-a-k-a", false));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/refresh-on-demand" +
                                "?count=1",
                        2L
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Yaga account is disabled"));

        verify(orchestrationService, never())
                .runOnDemand(anyLong(), anyInt(), anyString());
        verifyNoInteractions(runService);
        verifyNoInteractions(scheduler);
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new YagaOnDemandRefreshController(
                        accountService,
                        orchestrationService,
                        runService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private YagaAccount account(Long id, String shopSlug, boolean enabled) {
        YagaAccount account = new YagaAccount(
                shopSlug,
                shopSlug,
                "../playwright/.auth/yaga-state.json",
                10
        );
        account.setId(id);
        account.setEnabled(enabled);
        return account;
    }

    private YagaRefreshRunResponse runResponse(
            UUID runId,
            int selectedJobCount
    ) {
        return new YagaRefreshRunResponse(
                runId,
                2L,
                "w-a-k-a",
                YagaRefreshTriggerType.ON_DEMAND,
                YagaRefreshRunMode.AUTO,
                YagaRefreshRunStatus.COMPLETED,
                1,
                selectedJobCount,
                Instant.parse("2026-09-23T00:00:00Z"),
                Instant.parse("2026-09-23T00:00:10Z"),
                List.of()
        );
    }
}
