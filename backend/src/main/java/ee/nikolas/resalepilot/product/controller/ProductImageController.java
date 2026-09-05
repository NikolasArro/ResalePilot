package ee.nikolas.resalepilot.product.controller;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.dto.AddProductImageRequest;
import ee.nikolas.resalepilot.product.dto.ProductImageResponse;
import ee.nikolas.resalepilot.product.dto.ReorderProductImagesRequest;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.service.ProductImageService;
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

    @PatchMapping("/{imageId}/primary")
    public ProductImageResponse setPrimary(
            @PathVariable Long productId,
            @PathVariable Long imageId
    ) {
        return ProductImageResponse.from(
                productImageService.setPrimary(
                        productId,
                        imageId
                )
        );
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId
    ) {
        productImageService.deleteImage(
                productId,
                imageId
        );

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public List<ProductImageResponse> reorderImages(
            @PathVariable Long productId,
            @Valid @RequestBody ReorderProductImagesRequest request
    ) {
        return productImageService
                .reorderImages(productId, request.imageIds())
                .stream()
                .map(ProductImageResponse::from)
                .toList();
    }
}