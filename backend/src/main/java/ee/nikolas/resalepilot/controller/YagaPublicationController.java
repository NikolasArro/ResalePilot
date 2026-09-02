package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.service.YagaPublicationSessionManager;
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

    public YagaPublicationController(
            YagaPublicationSessionManager sessionManager
    ) {
        this.sessionManager = sessionManager;
    }

    @PostMapping("/listings/{listingId}/publication-preparations")
    public ResponseEntity<YagaPublicationPreparationResponse> prepare(
            @PathVariable Long listingId
    ) {
        return ResponseEntity.ok(
                sessionManager.prepare(listingId)
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

    @DeleteMapping("/publication-preparations/{preparationId}")
    public ResponseEntity<YagaPublicationPreparationStatusResponse> cancel(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(
                sessionManager.cancel(preparationId)
        );
    }
}
