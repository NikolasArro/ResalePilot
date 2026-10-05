package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaManualListingReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaManualListingReconcileResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/accounts/{accountId}/products/{productId}")
@ConditionalOnProperty(name = "yaga.publishing.enabled", havingValue = "true")
public class YagaManualListingReconciliationController {
    private final YagaManualListingReconciliationService service;

    public YagaManualListingReconciliationController(YagaManualListingReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/reconcile-manual-listing")
    public YagaManualListingReconcileResponse reconcile(@PathVariable Long accountId,
            @PathVariable Long productId, @Valid @RequestBody YagaManualListingReconcileRequest request) {
        return service.reconcile(accountId, productId, request);
    }
}
