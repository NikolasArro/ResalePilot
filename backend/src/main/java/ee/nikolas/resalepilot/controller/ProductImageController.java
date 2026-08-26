package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.AddProductImageRequest;
import ee.nikolas.resalepilot.dto.ProductImageResponse;
import ee.nikolas.resalepilot.entity.ProductImage;
import ee.nikolas.resalepilot.service.ProductImageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/products/{productId}/images")
public class ProductImageController {

    private final ProductImageService productImageService;

    public ProductImageController(
            ProductImageService productImageService
    ) {
        this.productImageService = productImageService;
    }

    @PostMapping
    public ResponseEntity<ProductImageResponse> addImage(
            @PathVariable Long productId,
            @Valid @RequestBody AddProductImageRequest request
    ) {
        ProductImage image = productImageService.addImage(
                productId,
                request.driveFileId(),
                request.fileName(),
                request.primary()
        );

        ProductImageResponse response =
                ProductImageResponse.from(image);

        return ResponseEntity
                .created(
                        URI.create(
                                "/api/products/" +
                                        productId +
                                        "/images/" +
                                        response.id()
                        )
                )
                .body(response);
    }

    @GetMapping
    public List<ProductImageResponse> getImages(
            @PathVariable Long productId
    ) {
        return productImageService.getImages(productId)
                .stream()
                .map(ProductImageResponse::from)
                .toList();
    }
}