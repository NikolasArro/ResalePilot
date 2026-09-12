package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshExecutionService;
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
                "&& '${yaga.refresh.execution-enabled:false}' == 'true'"
)
public class YagaRefreshExecutionController {

    private final YagaRefreshExecutionService executionService;

    public YagaRefreshExecutionController(
            YagaRefreshExecutionService executionService
    ) {
        this.executionService = executionService;
    }

    @PostMapping("/publication-preparation")
    public ResponseEntity<YagaRefreshPublicationPreparationResponse>
    preparePublication(
            @PathVariable UUID runId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(
                executionService.preparePublication(runId, jobId)
        );
    }

    @GetMapping("/publication-readiness")
    public ResponseEntity<YagaPublishReadinessResponse> publicationReadiness(
            @PathVariable UUID runId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(
                executionService.publicationReadiness(runId, jobId)
        );
    }

    @PostMapping("/confirm-publication")
    public ResponseEntity<YagaRefreshPublicationResultResponse>
    confirmPublication(
            @PathVariable UUID runId,
            @PathVariable UUID jobId,
            @Valid @RequestBody
            YagaRefreshPublicationConfirmRequest request
    ) {
        return ResponseEntity.ok(
                executionService.confirmPublication(runId, jobId, request)
        );
    }

    @PostMapping("/reconcile-publication")
    public ResponseEntity<YagaRefreshPublicationResultResponse>
    reconcilePublication(
            @PathVariable UUID runId,
            @PathVariable UUID jobId,
            @Valid @RequestBody
            YagaRefreshPublicationReconcileRequest request
    ) {
        return ResponseEntity.ok(
                executionService.reconcilePublication(runId, jobId, request)
        );
    }
}
