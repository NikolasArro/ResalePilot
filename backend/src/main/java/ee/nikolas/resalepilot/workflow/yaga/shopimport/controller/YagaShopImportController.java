package ee.nikolas.resalepilot.workflow.yaga.shopimport.controller;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaAccountBulkImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportPrepareRequest;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaShopImportRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaAccountBulkImportService;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaShopImportService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final YagaAccountBulkImportService accountBulkImportService;

    public YagaShopImportController(
            YagaShopImportService importService,
            YagaAccountBulkImportService accountBulkImportService
    ) {
        this.importService = importService;
        this.accountBulkImportService = accountBulkImportService;
    }

    @PostMapping("/api/yaga/accounts/{accountId}/import-listings")
    public ResponseEntity<YagaAccountBulkImportResponse> importAccountListings(
            @PathVariable Long accountId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Boolean onlyNew
    ) {
        return ResponseEntity.ok(
                accountBulkImportService.importCurrentListings(
                        accountId,
                        limit,
                        offset,
                        onlyNew
                )
        );
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
