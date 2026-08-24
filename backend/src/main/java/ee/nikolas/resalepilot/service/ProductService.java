package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.exception.DuplicateSkuException;
import ee.nikolas.resalepilot.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product create(Product product) {
        if (productRepository.existsBySku(product.getSku())) {
            throw new DuplicateSkuException(product.getSku());
        }

        return productRepository.save(product);
    }

    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public List<Product> getAll() {
        return productRepository.findAll();
    }
}