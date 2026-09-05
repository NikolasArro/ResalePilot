package ee.nikolas.resalepilot.integration.drive.controller;

import ee.nikolas.resalepilot.integration.drive.dto.GoogleDriveDiagnosticUploadResponse;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveDiagnosticService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/google-drive/diagnostics")
@ConditionalOnProperty(
        name = "google.drive.diagnostic-enabled",
        havingValue = "true"
)
public class GoogleDriveDiagnosticController {

    private final GoogleDriveDiagnosticService diagnosticService;

    public GoogleDriveDiagnosticController(
            GoogleDriveDiagnosticService diagnosticService
    ) {
        this.diagnosticService = diagnosticService;
    }

    @PostMapping("/upload-test")
    public GoogleDriveDiagnosticUploadResponse uploadTest() {
        return diagnosticService.uploadAndDeleteTestFile();
    }
}
