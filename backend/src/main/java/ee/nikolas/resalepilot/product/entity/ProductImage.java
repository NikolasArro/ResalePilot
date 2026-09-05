package ee.nikolas.resalepilot.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "product_images",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_product_images_drive_file",
                        columnNames = {"product_id", "drive_file_id"}
                )
        }
)
@Getter
@Setter
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_product_images_product"
            )
    )
    private Product product;

    @Column(name = "drive_file_id", nullable = false, length = 255)
    private String driveFileId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryImage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductImage() {
    }

    public ProductImage(Product product, String driveFileId) {
        this.product = product;
        this.driveFileId = driveFileId;
    }
}