package ee.nikolas.resalepilot.marketplace.entity;

import ee.nikolas.resalepilot.product.entity.ProductImage;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "marketplace_listing_images",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_listing_external_image",
                        columnNames = {
                                "marketplace_listing_id",
                                "external_image_id"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketplaceListingImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "marketplace_listing_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_listing_images_listing"
            )
    )
    private MarketplaceListing marketplaceListing;

    @Column(
            name = "external_image_id",
            nullable = false,
            length = 100
    )
    private String externalImageId;

    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_image_id",
            foreignKey = @ForeignKey(
                    name = "fk_listing_images_product_image"
            )
    )
    private ProductImage productImage;

    public MarketplaceListingImage(
            String externalImageId,
            String sourceUrl,
            String fileName,
            int displayOrder
    ) {
        this.externalImageId = externalImageId;
        this.sourceUrl = sourceUrl;
        this.fileName = fileName;
        this.displayOrder = displayOrder;
    }
}
