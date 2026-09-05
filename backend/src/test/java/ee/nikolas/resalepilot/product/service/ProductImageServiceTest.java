package ee.nikolas.resalepilot.product.service;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.product.exception.DuplicateProductImageException;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductImageService productImageService;

    @Test
    void shouldMakeFirstImagePrimary() {
        Product product = new Product(
                "RP-000001",
                "Nike jacket"
        );

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(productImageRepository
                .existsByProductIdAndDriveFileId(
                        1L,
                        "drive-file-1"
                ))
                .thenReturn(false);

        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.empty());

        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.empty());

        when(productImageRepository.save(any(ProductImage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        ProductImage result = productImageService.addImage(
                1L,
                "drive-file-1",
                "front.jpg",
                false
        );

        assertThat(result.getProduct()).isSameAs(product);
        assertThat(result.getDriveFileId())
                .isEqualTo("drive-file-1");
        assertThat(result.getFileName())
                .isEqualTo("front.jpg");
        assertThat(result.getDisplayOrder()).isZero();
        assertThat(result.isPrimaryImage()).isTrue();
    }

    @Test
    void shouldRejectDuplicateDriveFile() {
        Product product = new Product(
                "RP-000001",
                "Nike jacket"
        );

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(productImageRepository
                .existsByProductIdAndDriveFileId(
                        1L,
                        "drive-file-1"
                ))
                .thenReturn(true);

        assertThatThrownBy(() ->
                productImageService.addImage(
                        1L,
                        "drive-file-1",
                        "front.jpg",
                        false
                )
        )
                .isInstanceOf(
                        DuplicateProductImageException.class
                )
                .hasMessage(
                        "Drive file drive-file-1 " +
                                "is already attached to product 1"
                );

        verify(
                productImageRepository,
                never()
        ).save(any(ProductImage.class));
    }

    @Test
    void shouldReplacePrimaryImage() {
        Product product = new Product(
                "RP-000001",
                "Nike jacket"
        );

        ProductImage currentPrimary = new ProductImage(
                product,
                "drive-file-1"
        );
        currentPrimary.setDisplayOrder(0);
        currentPrimary.setPrimaryImage(true);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(productImageRepository
                .existsByProductIdAndDriveFileId(
                        1L,
                        "drive-file-2"
                ))
                .thenReturn(false);

        when(productImageRepository
                .findFirstByProductIdOrderByDisplayOrderDesc(1L))
                .thenReturn(Optional.of(currentPrimary));

        when(productImageRepository
                .findByProductIdAndPrimaryImageTrue(1L))
                .thenReturn(Optional.of(currentPrimary));

        when(productImageRepository.save(any(ProductImage.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        ProductImage result = productImageService.addImage(
                1L,
                "drive-file-2",
                "back.jpg",
                true
        );

        assertThat(currentPrimary.isPrimaryImage()).isFalse();
        assertThat(result.isPrimaryImage()).isTrue();
        assertThat(result.getDisplayOrder()).isEqualTo(1);

        verify(productImageRepository)
                .saveAndFlush(currentPrimary);
    }
}