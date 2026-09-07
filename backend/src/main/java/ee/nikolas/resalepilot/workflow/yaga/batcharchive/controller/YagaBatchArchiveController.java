package ee.nikolas.resalepilot.workflow.yaga.batcharchive.controller;

import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchiveConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchivePrepareRequest;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchiveRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.service.YagaBatchArchiveService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(
        prefix = "yaga.batch-archive",
        name = "enabled",
        havingValue = "true"
)
public class YagaBatchArchiveController {

    private final YagaBatchArchiveService service;

    public YagaBatchArchiveController(YagaBatchArchiveService service) {
        this.service = service;
    }

    @PostMapping("/api/yaga/batch-archive-runs")
    public ResponseEntity<YagaBatchArchiveRunResponse> prepare(
            @Valid @RequestBody YagaBatchArchivePrepareRequest request
    ) {
        return ResponseEntity.ok(
                service.prepare(
                        request.maxListings(),
                        request.idempotencyKey()
                )
        );
    }

    @GetMapping("/api/yaga/batch-archive-runs/{runId}")
    public ResponseEntity<YagaBatchArchiveRunResponse> get(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(service.get(runId));
    }

    @PostMapping("/api/yaga/batch-archive-runs/{runId}/confirm")
    public ResponseEntity<YagaBatchArchiveRunResponse> confirm(
            @PathVariable UUID runId,
            @RequestBody YagaBatchArchiveConfirmRequest request
    ) {
        return ResponseEntity.ok(
                service.confirm(runId, request.confirmationPhrase())
        );
    }

    @DeleteMapping("/api/yaga/batch-archive-runs/{runId}")
    public ResponseEntity<YagaBatchArchiveRunResponse> cancel(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(service.cancel(runId));
    }
}
