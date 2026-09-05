package ee.nikolas.resalepilot.product.controller;

import ee.nikolas.resalepilot.common.dto.PageResponse;
import ee.nikolas.resalepilot.product.dto.CreateProductRequest;
import ee.nikolas.resalepilot.product.dto.ProductResponse;
import ee.nikolas.resalepilot.product.dto.UpdateProductRequest;
import ee.nikolas.resalepilot.product.dto.UpdateProductStatusRequest;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody CreateProductRequest request
    ) {
        Product product = toEntity(request);
        Product createdProduct = productService.create(product);
        ProductResponse response = ProductResponse.from(createdProduct);

        return ResponseEntity
                .created(URI.create("/api/products/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return ProductResponse.from(productService.getById(id));
    }

    private Product toEntity(CreateProductRequest request) {
        Product product = new Product(request.sku(), request.title());

        product.setDescription(request.description());
        product.setCategory(request.category());
        product.setBrand(request.brand());
        product.setSize(request.size());
        product.setCondition(request.condition());
        product.setColor(request.color());
        product.setPurchasePrice(request.purchasePrice());
        product.setAskingPrice(request.askingPrice());
        product.setMinimumPrice(request.minimumPrice());
        product.setAcquiredAt(request.acquiredAt());

        return product;
    }

    @PutMapping("/{id}")
    public ProductResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        Product updatedProduct = productService.update(
                id,
                toEntity(request)
        );

        return ProductResponse.from(updatedProduct);
    }

    private Product toEntity(UpdateProductRequest request) {
        Product product = new Product(request.sku(), request.title());

        product.setDescription(request.description());
        product.setCategory(request.category());
        product.setBrand(request.brand());
        product.setSize(request.size());
        product.setCondition(request.condition());
        product.setColor(request.color());
        product.setPurchasePrice(request.purchasePrice());
        product.setAskingPrice(request.askingPrice());
        product.setMinimumPrice(request.minimumPrice());
        product.setAcquiredAt(request.acquiredAt());

        return product;
    }

    @PatchMapping("/{id}/status")
    public ProductResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductStatusRequest request
    ) {
        Product updatedProduct = productService.updateStatus(
                id,
                request.status()
        );

        return ProductResponse.from(updatedProduct);
    }

    @GetMapping
    public PageResponse<ProductResponse> getAll(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String title,

            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        return PageResponse.from(
                productService.search(
                        status,
                        brand,
                        category,
                        title,
                        pageable
                ),
                ProductResponse::from
        );
    }
}