package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshOperatorStateResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPrepareNextResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshOperatorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/yaga/refresh-runs/{runId}")
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.operator-enabled:false}' == 'true'"
)
public class YagaRefreshOperatorController {

    private final YagaRefreshOperatorService service;

    public YagaRefreshOperatorController(YagaRefreshOperatorService service) {
        this.service = service;
    }

    @GetMapping("/operator-state")
    public ResponseEntity<YagaRefreshOperatorStateResponse> operatorState(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(service.operatorState(runId));
    }

    @PostMapping("/prepare-next")
    public ResponseEntity<YagaRefreshPrepareNextResponse> prepareNext(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(service.prepareNext(runId));
    }
}
