package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.entity.ProductStatus;
import ee.nikolas.resalepilot.exception.DuplicateSkuException;
import ee.nikolas.resalepilot.exception.InvalidProductStatusTransitionException;
import ee.nikolas.resalepilot.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

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

    @Transactional
    public Product update(Long id, Product updatedProduct) {
        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        boolean skuChanged = !existingProduct.getSku()
                .equals(updatedProduct.getSku());

        if (skuChanged &&
                productRepository.existsBySku(updatedProduct.getSku())) {
            throw new DuplicateSkuException(updatedProduct.getSku());
        }

        existingProduct.setSku(updatedProduct.getSku());
        existingProduct.setTitle(updatedProduct.getTitle());
        existingProduct.setDescription(updatedProduct.getDescription());
        existingProduct.setCategory(updatedProduct.getCategory());
        existingProduct.setBrand(updatedProduct.getBrand());
        existingProduct.setSize(updatedProduct.getSize());
        existingProduct.setCondition(updatedProduct.getCondition());
        existingProduct.setColor(updatedProduct.getColor());
        existingProduct.setPurchasePrice(updatedProduct.getPurchasePrice());
        existingProduct.setAskingPrice(updatedProduct.getAskingPrice());
        existingProduct.setMinimumPrice(updatedProduct.getMinimumPrice());
        existingProduct.setAcquiredAt(updatedProduct.getAcquiredAt());

        return productRepository.save(existingProduct);
    }

    @Transactional
    public Product updateStatus(Long id, ProductStatus targetStatus) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        ProductStatus currentStatus = product.getStatus();

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new InvalidProductStatusTransitionException(
                    currentStatus,
                    targetStatus
            );
        }

        if (currentStatus == targetStatus) {
            return product;
        }

        product.setStatus(targetStatus);

        return productRepository.save(product);
    }

    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public List<Product> getAll() {
        return productRepository.findAll();
    }

    public List<Product> search(
            ProductStatus status,
            String brand,
            String category,
            String title
    ) {
        Specification<Product> specification =
                (root, query, criteriaBuilder) ->
                        criteriaBuilder.conjunction();

        if (status != null) {
            specification = specification.and(
                    (root, query, criteriaBuilder) ->
                            criteriaBuilder.equal(
                                    root.get("status"),
                                    status
                            )
            );
        }

        if (StringUtils.hasText(brand)) {
            String normalizedBrand =
                    brand.trim().toLowerCase(Locale.ROOT);

            specification = specification.and(
                    (root, query, criteriaBuilder) ->
                            criteriaBuilder.equal(
                                    criteriaBuilder.lower(root.get("brand")),
                                    normalizedBrand
                            )
            );
        }

        if (StringUtils.hasText(category)) {
            String normalizedCategory =
                    category.trim().toLowerCase(Locale.ROOT);

            specification = specification.and(
                    (root, query, criteriaBuilder) ->
                            criteriaBuilder.equal(
                                    criteriaBuilder.lower(root.get("category")),
                                    normalizedCategory
                            )
            );
        }

        if (StringUtils.hasText(title)) {
            String titlePattern =
                    "%" + title.trim().toLowerCase(Locale.ROOT) + "%";

            specification = specification.and(
                    (root, query, criteriaBuilder) ->
                            criteriaBuilder.like(
                                    criteriaBuilder.lower(root.get("title")),
                                    titlePattern
                            )
            );
        }

        return productRepository.findAll(specification);
    }
}