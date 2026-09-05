package ee.nikolas.resalepilot.product.service;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.exception.DuplicateSkuException;
import ee.nikolas.resalepilot.product.exception.InvalidProductStatusTransitionException;
import ee.nikolas.resalepilot.product.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void shouldCreateProduct() {
        Product product = new Product("RP-000001", "Nike jacket");

        when(productRepository.existsBySku("RP-000001"))
                .thenReturn(false);

        when(productRepository.save(product))
                .thenReturn(product);

        Product createdProduct = productService.create(product);

        assertThat(createdProduct).isSameAs(product);

        verify(productRepository).existsBySku("RP-000001");
        verify(productRepository).save(product);
    }

    @Test
    void shouldRejectDuplicateSku() {
        Product product = new Product("RP-000001", "Nike jacket");

        when(productRepository.existsBySku("RP-000001"))
                .thenReturn(true);

        assertThatThrownBy(() -> productService.create(product))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessage("Product already exists with SKU: RP-000001");

        verify(productRepository).existsBySku("RP-000001");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void shouldThrowExceptionWhenProductDoesNotExist() {
        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(999L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found with id: 999");

        verify(productRepository).findById(999L);
    }

    @Test
    void shouldUpdateProduct() {
        Product existingProduct =
                new Product("RP-000001", "Old jacket");

        Product updatedProduct =
                new Product("RP-000002", "Updated jacket");
        updatedProduct.setBrand("Nike");

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(existingProduct));

        when(productRepository.existsBySku("RP-000002"))
                .thenReturn(false);

        when(productRepository.save(existingProduct))
                .thenReturn(existingProduct);

        Product result = productService.update(1L, updatedProduct);

        assertThat(result.getSku()).isEqualTo("RP-000002");
        assertThat(result.getTitle()).isEqualTo("Updated jacket");
        assertThat(result.getBrand()).isEqualTo("Nike");

        verify(productRepository).findById(1L);
        verify(productRepository).existsBySku("RP-000002");
        verify(productRepository).save(existingProduct);
    }

    @Test
    void shouldRejectDuplicateSkuWhenUpdating() {
        Product existingProduct =
                new Product("RP-000001", "Old jacket");

        Product updatedProduct =
                new Product("RP-000002", "Updated jacket");

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(existingProduct));

        when(productRepository.existsBySku("RP-000002"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                productService.update(1L, updatedProduct)
        )
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessage("Product already exists with SKU: RP-000002");

        verify(productRepository).findById(1L);
        verify(productRepository).existsBySku("RP-000002");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void shouldThrowExceptionWhenUpdatingMissingProduct() {
        Product updatedProduct =
                new Product("RP-000002", "Updated jacket");

        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                productService.update(999L, updatedProduct)
        )
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product not found with id: 999");

        verify(productRepository).findById(999L);
        verify(productRepository, never()).existsBySku(anyString());
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void shouldUpdateProductStatus() {
        Product product = new Product("RP-000001", "Nike jacket");

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(productRepository.save(product))
                .thenReturn(product);

        Product result = productService.updateStatus(
                1L,
                ProductStatus.READY
        );

        assertThat(result.getStatus())
                .isEqualTo(ProductStatus.READY);

        verify(productRepository).findById(1L);
        verify(productRepository).save(product);
    }

    @Test
    void shouldRejectInvalidStatusTransition() {
        Product product = new Product("RP-000001", "Nike jacket");

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() ->
                productService.updateStatus(
                        1L,
                        ProductStatus.SOLD
                )
        )
                .isInstanceOf(
                        InvalidProductStatusTransitionException.class
                )
                .hasMessage(
                        "Cannot change product status from DRAFT to SOLD"
                );

        assertThat(product.getStatus())
                .isEqualTo(ProductStatus.DRAFT);

        verify(productRepository).findById(1L);
        verify(productRepository, never()).save(any(Product.class));
    }
}