package ee.nikolas.resalepilot.workflow.yaga.shopimport.controller;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportPrepareRequest;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaShopImportService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(
        prefix = "yaga.shop-import",
        name = "enabled",
        havingValue = "true"
)
public class YagaShopImportController {

    private final YagaShopImportService importService;

    public YagaShopImportController(
            YagaShopImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping("/api/yaga/shops/{shopSlug}/import-runs")
    public ResponseEntity<YagaShopImportRunResponse> prepare(
            @PathVariable String shopSlug,
            @Valid @RequestBody YagaShopImportPrepareRequest request
    ) {
        return ResponseEntity.ok(
                importService.prepare(
                        shopSlug,
                        request.maxItems(),
                        request.idempotencyKey()
                )
        );
    }

    @GetMapping("/api/yaga/shop-import-runs/{runId}")
    public ResponseEntity<YagaShopImportRunResponse> get(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(importService.get(runId));
    }

    @PostMapping("/api/yaga/shop-import-runs/{runId}/confirm")
    public ResponseEntity<YagaShopImportRunResponse> confirm(
            @PathVariable UUID runId,
            @RequestBody YagaShopImportConfirmRequest request
    ) {
        return ResponseEntity.ok(
                importService.confirm(
                        runId,
                        request.confirmationPhrase()
                )
        );
    }

    @DeleteMapping("/api/yaga/shop-import-runs/{runId}")
    public ResponseEntity<YagaShopImportRunResponse> cancel(
            @PathVariable UUID runId
    ) {
        return ResponseEntity.ok(importService.cancel(runId));
    }
}
