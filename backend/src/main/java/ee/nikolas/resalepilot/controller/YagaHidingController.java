package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.dto.YagaHidePreparationStatusResponse;
import ee.nikolas.resalepilot.dto.YagaHideReadinessResponse;
import ee.nikolas.resalepilot.dto.YagaHideConfirmRequest;
import ee.nikolas.resalepilot.dto.YagaHideConfirmResponse;
import ee.nikolas.resalepilot.service.YagaHidingSessionManager;
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
        name = "yaga.hiding.enabled",
        havingValue = "true"
)
public class YagaHidingController {

    private final YagaHidingSessionManager service;

    public YagaHidingController(
            YagaHidingSessionManager service
    ) {
        this.service = service;
    }

    @PostMapping("/listings/{oldListingId}/hide-preparations")
    public ResponseEntity<YagaHidePreparationResponse> prepareHide(
            @PathVariable Long oldListingId
    ) {
        return ResponseEntity.ok(
                service.prepare(oldListingId)
        );
    }

    @GetMapping("/hide-preparations/{preparationId}")
    public ResponseEntity<YagaHidePreparationStatusResponse> status(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(service.status(preparationId));
    }

    @GetMapping("/hide-preparations/{preparationId}/readiness")
    public ResponseEntity<YagaHideReadinessResponse> readiness(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(service.readiness(preparationId));
    }

    @PostMapping("/hide-preparations/{preparationId}/confirm")
    public ResponseEntity<YagaHideConfirmResponse> confirm(
            @PathVariable UUID preparationId,
            @RequestBody YagaHideConfirmRequest request
    ) {
        return ResponseEntity.ok(
                service.confirm(preparationId, request)
        );
    }

    @DeleteMapping("/hide-preparations/{preparationId}")
    public ResponseEntity<YagaHidePreparationStatusResponse> cancel(
            @PathVariable UUID preparationId
    ) {
        return ResponseEntity.ok(service.cancel(preparationId));
    }
}
