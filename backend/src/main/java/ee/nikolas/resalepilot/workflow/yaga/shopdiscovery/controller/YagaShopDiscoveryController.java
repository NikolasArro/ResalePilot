package ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.controller;

import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.dto.YagaShopDiscoveryResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopdiscovery.service.YagaShopDiscoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/shops")
@ConditionalOnProperty(
        prefix = "yaga.shop-discovery",
        name = "enabled",
        havingValue = "true"
)
public class YagaShopDiscoveryController {

    private final YagaShopDiscoveryService discoveryService;

    public YagaShopDiscoveryController(
            YagaShopDiscoveryService discoveryService
    ) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/{shopSlug}/discovery")
    public ResponseEntity<YagaShopDiscoveryResponse> discover(
            @PathVariable String shopSlug
    ) {
        return ResponseEntity.ok(discoveryService.discover(shopSlug));
    }
}
