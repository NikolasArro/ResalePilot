package ee.nikolas.resalepilot.workflow.yaga.refresh.controller;

import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideRecoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.service.YagaRefreshHideRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/yaga/refresh-runs/{runId}/jobs/{jobId}")
@ConditionalOnProperty(name = "yaga.refresh.enabled", havingValue = "true")
public class YagaRefreshHideRecoveryController {
    private final YagaRefreshHideRecoveryService service;

    public YagaRefreshHideRecoveryController(YagaRefreshHideRecoveryService service) {
        this.service = service;
    }

    @PostMapping("/recover-hide-preparation")
    public YagaRefreshHideRecoveryResponse recover(@PathVariable UUID runId, @PathVariable UUID jobId) {
        return service.recover(runId, jobId);
    }
}
