package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.entity.ProductImage;
import ee.nikolas.resalepilot.exception.DuplicateProductImageException;
import ee.nikolas.resalepilot.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.repository.ProductImageRepository;
import ee.nikolas.resalepilot.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductImageService {

    private final ProductImageRepository productImageRepository;
    private final ProductRepository productRepository;

    public ProductImageService(
            ProductImageRepository productImageRepository,
            ProductRepository productRepository
    ) {
        this.productImageRepository = productImageRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductImage addImage(
            Long productId,
            String driveFileId,
            String fileName,
            boolean primary
    ) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() ->
                        new ProductNotFoundException(productId)
                );

        if (productImageRepository
                .existsByProductIdAndDriveFileId(
                        productId,
                        driveFileId
                )) {
            throw new DuplicateProductImageException(
                    productId,
                    driveFileId
            );
        }

        int displayOrder = productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(productId)
                .map(image -> image.getDisplayOrder() + 1)
                .orElse(0);

        boolean shouldBePrimary = primary || displayOrder == 0;

        if (shouldBePrimary) {
            productImageRepository
                    .findByProductIdAndPrimaryImageTrue(productId)
                    .ifPresent(currentPrimary -> {
                        currentPrimary.setPrimaryImage(false);

                        /*
                         * Выполняем UPDATE до вставки нового главного фото,
                         * чтобы не нарушить уникальный индекс PostgreSQL.
                         */
                        productImageRepository.saveAndFlush(
                                currentPrimary
                        );
                    });
        }

        ProductImage image = new ProductImage(
                product,
                driveFileId
        );

        image.setFileName(fileName);
        image.setDisplayOrder(displayOrder);
        image.setPrimaryImage(shouldBePrimary);

        return productImageRepository.save(image);
    }

    public List<ProductImage> getImages(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }

        return productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(productId);
    }
}