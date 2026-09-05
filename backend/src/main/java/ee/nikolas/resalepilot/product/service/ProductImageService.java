package ee.nikolas.resalepilot.product.service;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.exception.DuplicateProductImageException;
import ee.nikolas.resalepilot.product.exception.ProductImageNotFoundException;
import ee.nikolas.resalepilot.product.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.product.exception.InvalidProductImageOrderException;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    @Transactional
    public ProductImage setPrimary(
            Long productId,
            Long imageId
    ) {
        ProductImage targetImage = productImageRepository
                .findByIdAndProductId(imageId, productId)
                .orElseThrow(() ->
                        new ProductImageNotFoundException(
                                productId,
                                imageId
                        )
                );

        if (targetImage.isPrimaryImage()) {
            return targetImage;
        }

        productImageRepository
                .findByProductIdAndPrimaryImageTrue(productId)
                .ifPresent(currentPrimary -> {
                    currentPrimary.setPrimaryImage(false);
                    productImageRepository.saveAndFlush(currentPrimary);
                });

        targetImage.setPrimaryImage(true);

        return productImageRepository.save(targetImage);
    }

    @Transactional
    public void deleteImage(
            Long productId,
            Long imageId
    ) {
        ProductImage image = productImageRepository
                .findByIdAndProductId(imageId, productId)
                .orElseThrow(() ->
                        new ProductImageNotFoundException(
                                productId,
                                imageId
                        )
                );

        boolean wasPrimary = image.isPrimaryImage();

        productImageRepository.delete(image);

        /*
         * Выполняем DELETE сразу, чтобы уникальный индекс
         * позволил назначить другую фотографию главной.
         */
        productImageRepository.flush();

        List<ProductImage> remainingImages =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                productId
                        );

        for (int index = 0; index < remainingImages.size(); index++) {
            remainingImages.get(index).setDisplayOrder(index);
        }

        if (wasPrimary && !remainingImages.isEmpty()) {
            remainingImages.getFirst().setPrimaryImage(true);
        }

        productImageRepository.saveAll(remainingImages);
    }

    @Transactional
    public List<ProductImage> reorderImages(
            Long productId,
            List<Long> imageIds
    ) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }

        List<ProductImage> currentImages =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                productId
                        );

        Set<Long> requestedIds = new HashSet<>(imageIds);

        boolean containsEveryImage =
                imageIds.size() == currentImages.size() &&
                        requestedIds.size() == imageIds.size() &&
                        currentImages.stream()
                                .map(ProductImage::getId)
                                .allMatch(requestedIds::contains);

        if (!containsEveryImage) {
            throw new InvalidProductImageOrderException(productId);
        }

        Map<Long, ProductImage> imagesById = new HashMap<>();

        currentImages.forEach(image ->
                imagesById.put(image.getId(), image)
        );

        List<ProductImage> reorderedImages =
                new java.util.ArrayList<>();

        for (int index = 0; index < imageIds.size(); index++) {
            ProductImage image = imagesById.get(imageIds.get(index));
            image.setDisplayOrder(index);
            reorderedImages.add(image);
        }

        productImageRepository.saveAll(reorderedImages);

        return reorderedImages;
    }
}