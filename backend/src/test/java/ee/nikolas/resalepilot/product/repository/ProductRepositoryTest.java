package ee.nikolas.resalepilot.product.repository;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.product.entity.ProductImage;
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
    @Autowired
    private ProductImageRepository productImageRepository;

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

    @Test
    void shouldSaveProductImagesInDisplayOrder() {
        Product product = new Product(
                "RP-IMAGE-001",
                "Nike jacket with images"
        );

        Product savedProduct =
                productRepository.saveAndFlush(product);

        ProductImage backImage = new ProductImage(
                savedProduct,
                "drive-file-back"
        );
        backImage.setFileName("nike-jacket-back.jpg");
        backImage.setDisplayOrder(1);

        ProductImage frontImage = new ProductImage(
                savedProduct,
                "drive-file-front"
        );
        frontImage.setFileName("nike-jacket-front.jpg");
        frontImage.setDisplayOrder(0);
        frontImage.setPrimaryImage(true);

        productImageRepository.saveAllAndFlush(
                java.util.List.of(backImage, frontImage)
        );

        var images =
                productImageRepository
                        .findAllByProductIdOrderByDisplayOrderAsc(
                                savedProduct.getId()
                        );

        assertThat(images).hasSize(2);

        assertThat(images)
                .extracting(ProductImage::getDriveFileId)
                .containsExactly(
                        "drive-file-front",
                        "drive-file-back"
                );

        ProductImage primaryImage =
                productImageRepository
                        .findByProductIdAndPrimaryImageTrue(
                                savedProduct.getId()
                        )
                        .orElseThrow();

        assertThat(primaryImage.getDriveFileId())
                .isEqualTo("drive-file-front");

        assertThat(
                productImageRepository.existsByProductIdAndDriveFileId(
                        savedProduct.getId(),
                        "drive-file-back"
                )
        ).isTrue();
    }
}