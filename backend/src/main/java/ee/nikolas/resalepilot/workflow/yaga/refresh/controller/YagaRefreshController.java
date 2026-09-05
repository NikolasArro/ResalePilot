package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshRunService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/yaga/refresh-runs")
@ConditionalOnProperty(
        prefix = "yaga.refresh",
        name = "enabled",
        havingValue = "true"
)
public class YagaRefreshController {

    private final YagaRefreshRunService refreshRunService;

    public YagaRefreshController(
            YagaRefreshRunService refreshRunService
    ) {
        this.refreshRunService = refreshRunService;
    }

    @PostMapping
    public ResponseEntity<YagaRefreshRunResponse> startManualDryRun(
            @Valid @RequestBody YagaRefreshRunRequest request
    ) {
        return ResponseEntity.ok(
                refreshRunService.startManualDryRun(request)
        );
    }

    @GetMapping("/{runId}")
    public ResponseEntity<YagaRefreshRunResponse> getRun(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(refreshRunService.getRun(runId));
    }
}
