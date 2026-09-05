package ee.nikolas.resalepilot.marketplace.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "marketplace_listing_categories",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_listing_category_level",
                        columnNames = {
                                "marketplace_listing_id",
                                "category_level"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketplaceListingCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "marketplace_listing_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_listing_categories_listing"
            )
    )
    private MarketplaceListing marketplaceListing;

    @Column(name = "category_level", nullable = false)
    private int categoryLevel;

    @Column(name = "external_category_id", nullable = false)
    private Long externalCategoryId;

    @Column(name = "parent_external_category_id")
    private Long parentExternalCategoryId;

    @Column(nullable = false, length = 200)
    private String title;

    public MarketplaceListingCategory(
            int categoryLevel,
            Long externalCategoryId,
            Long parentExternalCategoryId,
            String title
    ) {
        this.categoryLevel = categoryLevel;
        this.externalCategoryId = externalCategoryId;
        this.parentExternalCategoryId =
                parentExternalCategoryId;
        this.title = title;
    }
}