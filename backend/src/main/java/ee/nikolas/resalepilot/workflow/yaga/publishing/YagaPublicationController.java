package ee.nikolas.resalepilot.workflow.yaga.publishing;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaListingPublicationReconcileResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.YagaPublicationReconciliationService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublicationSessionManager;
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
@RequestMapping("/api/yaga")
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class YagaPublicationController {

    private final YagaPublicationSessionManager sessionManager;
    private final YagaPublicationReconciliationService reconciliationService;

    public YagaPublicationController(
            YagaPublicationSessionManager sessionManager,
            YagaPublicationReconciliationService reconciliationService
    ) {
        this.sessionManager = sessionManager;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/listings/{listingId}/publication-preparations")
    public ResponseEntity<YagaPublicationPreparationResponse> prepare(
            @PathVariable Long listingId
    ) {
        return ResponseEntity.ok(
                sessionManager.prepare(listingId)
        );
    }

    @PostMapping("/listings/{oldListingId}/reconcile-publication")
    public ResponseEntity<YagaListingPublicationReconcileResponse>
    reconcilePublication(
            @PathVariable Long oldListingId,
            @Valid @RequestBody
            YagaListingPublicationReconcileRequest request
    ) {
        return ResponseEntity.ok(
                reconciliationService.reconcile(oldListingId, request)
        );
    }

    @GetMapping("/publication-preparations/{preparationId}")
    public ResponseEntity<YagaPublicationPreparationStatusResponse> status(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(
                sessionManager.status(preparationId)
        );
    }

    @GetMapping("/publication-preparations/{preparationId}/publish-readiness")
    public ResponseEntity<YagaPublishReadinessResponse> publishReadiness(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(
                sessionManager.publishReadiness(preparationId)
        );
    }

    @PostMapping("/publication-preparations/{preparationId}/confirm")
    public ResponseEntity<YagaPublicationConfirmResponse> confirm(
            @PathVariable UUID preparationId,
            @Valid @RequestBody YagaPublicationConfirmRequest request
    ) {
        return ResponseEntity.ok(
                sessionManager.confirm(preparationId, request)
        );
    }

    @PostMapping("/publication-preparations/{preparationId}/reconcile")
    public ResponseEntity<YagaPublicationConfirmResponse> reconcile(
            @PathVariable UUID preparationId,
            @RequestBody(required = false)
            YagaPublicationReconcileRequest request
    ) {
        return ResponseEntity.ok(
                sessionManager.reconcile(preparationId, request)
        );
    }

    @DeleteMapping("/publication-preparations/{preparationId}")
    public ResponseEntity<YagaPublicationPreparationStatusResponse> cancel(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(
                sessionManager.cancel(preparationId)
        );
    }
}
