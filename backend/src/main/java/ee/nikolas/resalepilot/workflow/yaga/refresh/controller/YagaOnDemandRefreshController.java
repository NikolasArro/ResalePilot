package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaOnDemandRefreshResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.scheduler.YagaRefreshAutoRunResult;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshAutoOrchestrationService;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshRunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/yaga/accounts")
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.hide-execution-enabled:false}' == 'true'"
)
public class YagaOnDemandRefreshController {

    private final YagaAccountService accountService;
    private final YagaRefreshAutoOrchestrationService orchestrationService;
    private final YagaRefreshRunService runService;

    public YagaOnDemandRefreshController(
            YagaAccountService accountService,
            YagaRefreshAutoOrchestrationService orchestrationService,
            YagaRefreshRunService runService
    ) {
        this.accountService = accountService;
        this.orchestrationService = orchestrationService;
        this.runService = runService;
    }

    @PostMapping("/{accountId}/refresh-on-demand")
    public ResponseEntity<YagaOnDemandRefreshResponse> refreshOnDemand(
            @PathVariable Long accountId,
            @RequestParam int count,
            @RequestParam(required = false) String idempotencyKey
    ) {
        if (count < 1) {
            throw new YagaRefreshRequestInvalidException(
                    "count must be at least 1"
            );
        }

        YagaAccount account = accountService.getEntity(accountId);
        if (!account.isEnabled()) {
            throw new YagaRefreshRequestInvalidException(
                    "Yaga account is disabled"
            );
        }

        YagaRefreshAutoRunResult result = orchestrationService.runOnDemand(
                accountId,
                count,
                effectiveIdempotencyKey(idempotencyKey)
        );

        return response(result);
    }

    @PostMapping("/{accountId}/refresh-on-demand/listings/{listingId}")
    public ResponseEntity<YagaOnDemandRefreshResponse> refreshListingOnDemand(
            @PathVariable Long accountId,
            @PathVariable Long listingId,
            @RequestParam(required = false) String idempotencyKey
    ) {
        YagaAccount account = accountService.getEntity(accountId);
        if (!account.isEnabled()) {
            throw new YagaRefreshRequestInvalidException(
                    "Yaga account is disabled"
            );
        }
        return response(orchestrationService.runOnDemandListing(
                accountId, listingId, effectiveIdempotencyKey(idempotencyKey)
        ));
    }

    private ResponseEntity<YagaOnDemandRefreshResponse> response(
            YagaRefreshAutoRunResult result
    ) {
        Integer selectedJobCount = null;
        if (result.runId() != null) {
            YagaRefreshRunResponse run = runService.getRun(result.runId());
            selectedJobCount = run.selectedJobCount();
        }

        return ResponseEntity.ok(new YagaOnDemandRefreshResponse(
                result.runId(),
                result.status(),
                result.completed(),
                selectedJobCount,
                result.safeMessage()
        ));
    }

    private String effectiveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return "http-on-demand-" + UUID.randomUUID();
    }
}
