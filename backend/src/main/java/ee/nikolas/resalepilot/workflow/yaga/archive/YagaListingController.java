package ee.nikolas.resalepilot.workflow.yaga.archive;

import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/listings")
public class YagaListingController {

    private final YagaImageArchiveService imageArchiveService;

    public YagaListingController(
            YagaImageArchiveService imageArchiveService
    ) {
        this.imageArchiveService = imageArchiveService;
    }

    @PostMapping("/{listingId}/archive-images")
    public YagaArchiveImagesResponse archiveListingImages(
            @PathVariable Long listingId
    ) {
        return imageArchiveService.archiveImages(listingId);
    }
}
