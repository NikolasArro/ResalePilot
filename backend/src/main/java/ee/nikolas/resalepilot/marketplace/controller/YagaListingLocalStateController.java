package ee.nikolas.resalepilot.marketplace.controller;

import ee.nikolas.resalepilot.marketplace.dto.MarketplaceListingStatusResponse;
import ee.nikolas.resalepilot.marketplace.dto.MarketplaceListingStatusUpdateRequest;
import ee.nikolas.resalepilot.marketplace.dto.YagaListingReconciliationResponse;
import ee.nikolas.resalepilot.marketplace.service.YagaListingLocalStateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/accounts/{accountId}")
public class YagaListingLocalStateController {

    private final YagaListingLocalStateService service;

    public YagaListingLocalStateController(
            YagaListingLocalStateService service
    ) {
        this.service = service;
    }

    @PatchMapping("/listings/by-slug/{productSlug}/status")
    public MarketplaceListingStatusResponse updateStatusBySlug(
            @PathVariable Long accountId,
            @PathVariable String productSlug,
            @Valid @RequestBody MarketplaceListingStatusUpdateRequest request
    ) {
        return service.updateStatusBySlug(
                accountId,
                productSlug,
                request.status()
        );
    }

    @PostMapping("/reconcile-listings")
    public YagaListingReconciliationResponse reconcile(
            @PathVariable Long accountId
    ) {
        return service.reconcile(accountId);
    }
}
