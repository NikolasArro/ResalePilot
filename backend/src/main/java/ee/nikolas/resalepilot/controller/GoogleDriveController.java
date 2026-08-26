package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.GoogleDriveFileResponse;
import ee.nikolas.resalepilot.service.GoogleDriveService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/google-drive/files")
@ConditionalOnBean(GoogleDriveService.class)
public class GoogleDriveController {

    private final GoogleDriveService googleDriveService;

    public GoogleDriveController(
            GoogleDriveService googleDriveService
    ) {
        this.googleDriveService = googleDriveService;
    }

    @GetMapping("/{fileId}")
    public GoogleDriveFileResponse getFileMetadata(
            @PathVariable String fileId
    ) {
        return googleDriveService.getFileMetadata(fileId);
    }
}