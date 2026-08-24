package ee.nikolas.resalepilot.repository;

import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.entity.ProductCondition;
import ee.nikolas.resalepilot.entity.ProductStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Transactional
class ProductRepositoryTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("resalepilot")
                    .withUsername("resalepilot")
                    .withPassword("resalepilot");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;

    @Test
    void shouldSaveAndFindProductBySku() {
        Product product = new Product("RP-000001", "Nike vintage jacket");
        product.setBrand("Nike");
        product.setCondition(ProductCondition.VERY_GOOD);
        product.setAskingPrice(new BigDecimal("35.00"));

        Product savedProduct = productRepository.saveAndFlush(product);

        assertThat(savedProduct.getId()).isNotNull();
        assertThat(savedProduct.getStatus()).isEqualTo(ProductStatus.DRAFT);

        Product foundProduct = productRepository.findBySku("RP-000001")
                .orElseThrow();

        assertThat(foundProduct.getTitle()).isEqualTo("Nike vintage jacket");
        assertThat(foundProduct.getBrand()).isEqualTo("Nike");
        assertThat(foundProduct.getAskingPrice())
                .isEqualByComparingTo("35.00");
        assertThat(productRepository.existsBySku("RP-000001")).isTrue();
    }
}