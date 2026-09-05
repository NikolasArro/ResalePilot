package ee.nikolas.resalepilot.workflow.yaga.publishing;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPrepareFormResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.YagaPublishingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/listings")
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class YagaPublishingController {

    private final YagaPublishingService publishingService;

    public YagaPublishingController(
            YagaPublishingService publishingService
    ) {
        this.publishingService = publishingService;
    }

    @PostMapping("/{listingId}/prepare-form")
    public ResponseEntity<YagaPrepareFormResponse> prepareForm(
            @PathVariable Long listingId
    ) {
        return ResponseEntity.ok(
                publishingService.prepareForm(listingId)
        );
    }
}
