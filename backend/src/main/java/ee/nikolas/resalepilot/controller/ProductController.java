package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.CreateProductRequest;
import ee.nikolas.resalepilot.dto.ProductResponse;
import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.service.ProductService;
import jakarta.validation.Valid;
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

    @GetMapping
    public List<ProductResponse> getAll() {
        return productService.getAll()
                .stream()
                .map(ProductResponse::from)
                .toList();
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
}