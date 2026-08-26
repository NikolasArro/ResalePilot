package ee.nikolas.resalepilot.repository;

import ee.nikolas.resalepilot.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductImageRepository
        extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findAllByProductIdOrderByDisplayOrderAsc(
            Long productId
    );

    Optional<ProductImage> findByIdAndProductId(
            Long id,
            Long productId
    );

    Optional<ProductImage> findByProductIdAndPrimaryImageTrue(
            Long productId
    );

    Optional<ProductImage> findFirstByProductIdOrderByDisplayOrderDesc(
            Long productId
    );

    boolean existsByProductIdAndDriveFileId(
            Long productId,
            String driveFileId
    );
}