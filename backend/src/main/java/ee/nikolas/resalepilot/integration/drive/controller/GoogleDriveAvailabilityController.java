package ee.nikolas.resalepilot.integration.drive.controller;

import ee.nikolas.resalepilot.integration.drive.config.GoogleDriveProperties;
import ee.nikolas.resalepilot.integration.drive.dto.GoogleDriveAvailabilityResponse;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("/api/drive")
@ConditionalOnProperty(
        name = "google.drive.enabled",
        havingValue = "true"
)
public class GoogleDriveAvailabilityController {

    private final GoogleDriveService googleDriveService;
    private final GoogleDriveProperties properties;
    private final Clock clock;

    public GoogleDriveAvailabilityController(
            GoogleDriveService googleDriveService,
            GoogleDriveProperties properties,
            Clock clock
    ) {
        this.googleDriveService = googleDriveService;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/availability")
    public GoogleDriveAvailabilityResponse availability() {
        googleDriveService.verifyAvailable();

        return new GoogleDriveAvailabilityResponse(
                true,
                properties.authMode(),
                clock.instant()
        );
    }
}
