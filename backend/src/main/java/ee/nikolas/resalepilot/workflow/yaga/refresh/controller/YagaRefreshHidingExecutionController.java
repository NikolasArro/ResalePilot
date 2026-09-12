package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshHidingExecutionService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/yaga/refresh-runs/{runId}/jobs/{jobId}")
@ConditionalOnExpression(
        "'${yaga.refresh.enabled:false}' == 'true' " +
                "&& '${yaga.refresh.execution-enabled:false}' == 'true' " +
                "&& '${yaga.refresh.hide-execution-enabled:false}' == 'true' " +
                "&& '${yaga.hiding.enabled:false}' == 'true'"
)
public class YagaRefreshHidingExecutionController {

    private final YagaRefreshHidingExecutionService service;

    public YagaRefreshHidingExecutionController(
            YagaRefreshHidingExecutionService service
    ) {
        this.service = service;
    }

    @PostMapping("/hide-preparation")
    public ResponseEntity<YagaRefreshHidePreparationResponse> prepare(
            @PathVariable UUID runId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(service.prepare(runId, jobId));
    }

    @GetMapping("/hide-readiness")
    public ResponseEntity<YagaRefreshHideReadinessResponse> readiness(
            @PathVariable UUID runId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(service.readiness(runId, jobId));
    }

    @PostMapping("/confirm-hide")
    public ResponseEntity<YagaRefreshHideResultResponse> confirm(
            @PathVariable UUID runId,
            @PathVariable UUID jobId,
            @Valid @RequestBody YagaRefreshHideConfirmRequest request
    ) {
        return ResponseEntity.ok(service.confirm(runId, jobId, request));
    }

    @PostMapping("/reconcile-hide")
    public ResponseEntity<YagaRefreshHideResultResponse> reconcile(
            @PathVariable UUID runId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(service.reconcile(runId, jobId));
    }
}
