package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.exception.DuplicateSkuException;
import ee.nikolas.resalepilot.exception.ProductNotFoundException;
import ee.nikolas.resalepilot.repository.ProductRepository;
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
}