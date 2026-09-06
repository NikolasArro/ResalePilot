package ee.nikolas.resalepilot.workflow.yaga.shopimport.entity;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "yaga_shop_import_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_yaga_shop_import_items_run_order",
                columnNames = {"run_id", "selection_order"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaShopImportItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "run_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_shop_import_items_run")
    )
    private YagaShopImportRun run;

    @Column(name = "selection_order", nullable = false)
    private int selectionOrder;

    @Column(name = "external_listing_id", length = 100)
    private String externalListingId;

    @Column(name = "shop_slug", nullable = false, length = 150)
    private String shopSlug;

    @Column(name = "product_slug", nullable = false, length = 150)
    private String productSlug;

    @Column(name = "selected_title", length = 150)
    private String selectedTitle;

    @Column(name = "public_url", nullable = false, columnDefinition = "TEXT")
    private String publicUrl;

    @Column(name = "discovered_status", nullable = false, length = 50)
    private String discoveredStatus;

    @Column(name = "external_created_at")
    private Instant externalCreatedAt;

    @Column(name = "expected_image_count", nullable = false)
    private int expectedImageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaShopImportItemStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_id",
            foreignKey = @ForeignKey(name = "fk_yaga_shop_import_items_product")
    )
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "marketplace_listing_id",
            foreignKey = @ForeignKey(name = "fk_yaga_shop_import_items_listing")
    )
    private MarketplaceListing marketplaceListing;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_safe_error_message", columnDefinition = "TEXT")
    private String lastSafeErrorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaShopImportItem(
            int selectionOrder,
            String externalListingId,
            String shopSlug,
            String productSlug,
            String selectedTitle,
            String publicUrl,
            String discoveredStatus,
            Instant externalCreatedAt,
            int expectedImageCount,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.selectionOrder = selectionOrder;
        this.externalListingId = externalListingId;
        this.shopSlug = shopSlug;
        this.productSlug = productSlug;
        this.selectedTitle = selectedTitle;
        this.publicUrl = publicUrl;
        this.discoveredStatus = discoveredStatus;
        this.externalCreatedAt = externalCreatedAt;
        this.expectedImageCount = expectedImageCount;
        this.status = YagaShopImportItemStatus.SELECTED;
        this.createdAt = now;
    }
}
