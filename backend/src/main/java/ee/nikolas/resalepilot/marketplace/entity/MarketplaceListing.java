package ee.nikolas.resalepilot.marketplace.entity;

import ee.nikolas.resalepilot.product.entity.Product;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "marketplace_listings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_marketplace_external_listing",
                        columnNames = {
                                "marketplace",
                                "external_listing_id"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketplaceListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_marketplace_listings_product"
            )
    )
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Marketplace marketplace;

    @Column(
            name = "external_listing_id",
            nullable = false,
            length = 100
    )
    private String externalListingId;

    @Column(name = "shop_slug", length = 150)
    private String shopSlug;

    @Column(name = "product_slug", length = 150)
    private String productSlug;

    @Column(name = "external_url", nullable = false, columnDefinition = "TEXT")
    private String externalUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MarketplaceListingStatus status =
            MarketplaceListingStatus.UNKNOWN;

    @Column(name = "external_status", length = 50)
    private String externalStatus;

    @Column(name = "external_condition_id")
    private Long externalConditionId;

    @Column(name = "external_condition_name", length = 100)
    private String externalConditionName;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "external_created_at")
    private Instant externalCreatedAt;

    @Column(name = "external_updated_at")
    private Instant externalUpdatedAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @OneToMany(
            mappedBy = "marketplaceListing",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("categoryLevel ASC")
    private List<MarketplaceListingCategory> categories =
            new ArrayList<>();

    @OneToMany(
            mappedBy = "marketplaceListing",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("displayOrder ASC")
    private List<MarketplaceListingImage> images =
            new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public MarketplaceListing(
            Product product,
            Marketplace marketplace,
            String externalListingId,
            String externalUrl
    ) {
        this.product = product;
        this.marketplace = marketplace;
        this.externalListingId = externalListingId;
        this.externalUrl = externalUrl;
        this.lastSyncedAt = Instant.now();
    }

    public void addCategory(
            MarketplaceListingCategory category
    ) {
        category.setMarketplaceListing(this);
        categories.add(category);
    }

    public void addImage(
            MarketplaceListingImage image
    ) {
        image.setMarketplaceListing(this);
        images.add(image);
    }
}