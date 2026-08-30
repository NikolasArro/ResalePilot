package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.YagaImportPreviewRequest;
import ee.nikolas.resalepilot.dto.YagaImportRequest;
import ee.nikolas.resalepilot.dto.YagaImportResponse;
import ee.nikolas.resalepilot.service.YagaImportService;
import ee.nikolas.resalepilot.yaga.YagaImportedProductData;
import ee.nikolas.resalepilot.yaga.YagaPageDataClient;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yaga/import")
public class YagaImportController {

    private final YagaPageDataClient pageDataClient;
    private final YagaImportService importService;

    public YagaImportController(
            YagaPageDataClient pageDataClient,
            YagaImportService importService
    ) {
        this.pageDataClient = pageDataClient;
        this.importService = importService;
    }

    @PostMapping("/preview")
    public YagaImportedProductData previewImport(
            @Valid @RequestBody
            YagaImportPreviewRequest request
    ) {
        return pageDataClient.getProduct(
                request.productUrl()
        );
    }

    @PostMapping
    public YagaImportResponse importProduct(
            @Valid @RequestBody
            YagaImportRequest request
    ) {
        return importService.importProduct(request);
    }
}
