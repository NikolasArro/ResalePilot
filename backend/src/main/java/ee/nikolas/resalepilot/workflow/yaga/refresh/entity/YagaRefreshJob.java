package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "yaga_refresh_jobs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaRefreshJob {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "run_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_refresh_jobs_run")
    )
    private YagaRefreshRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_refresh_jobs_product")
    )
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "old_listing_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_refresh_jobs_old_listing")
    )
    private MarketplaceListing oldListing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "new_listing_id",
            foreignKey = @ForeignKey(name = "fk_yaga_refresh_jobs_new_listing")
    )
    private MarketplaceListing newListing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaRefreshJobStatus status;

    @Column(name = "selection_order", nullable = false)
    private int selectionOrder;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "new_product_url", columnDefinition = "TEXT")
    private String newProductUrl;

    @Column(name = "publication_preparation_id")
    private UUID publicationPreparationId;

    @Column(name = "publication_status", length = 50)
    private String publicationStatus;

    @Column(name = "new_external_listing_id", length = 100)
    private String newExternalListingId;

    @Column(name = "new_shop_slug", length = 150)
    private String newShopSlug;

    @Column(name = "new_product_slug", length = 150)
    private String newProductSlug;

    @Column(name = "publication_prepared_at")
    private Instant publicationPreparedAt;

    @Column(name = "publication_confirm_started_at")
    private Instant publicationConfirmStartedAt;

    @Column(name = "publication_confirmed_at")
    private Instant publicationConfirmedAt;

    @Column(name = "old_external_listing_id", length = 100)
    private String oldExternalListingId;

    @Column(name = "old_shop_slug", length = 150)
    private String oldShopSlug;

    @Column(name = "old_product_slug", length = 150)
    private String oldProductSlug;

    @Column(name = "old_public_url", columnDefinition = "TEXT")
    private String oldPublicUrl;

    @Column(name = "product_title", length = 150)
    private String productTitle;

    @Column(name = "selected_external_created_at")
    private Instant selectedExternalCreatedAt;

    @Column(name = "selected_listing_created_at")
    private Instant selectedListingCreatedAt;

    @Column(name = "expected_product_image_count")
    private Integer expectedProductImageCount;

    @Column(name = "expected_listing_image_count")
    private Integer expectedListingImageCount;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_safe_error_message", columnDefinition = "TEXT")
    private String lastSafeErrorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaRefreshJob(
            Product product,
            MarketplaceListing oldListing,
            String oldExternalListingId,
            String oldShopSlug,
            String oldProductSlug,
            String oldPublicUrl,
            String productTitle,
            Instant selectedExternalCreatedAt,
            Instant selectedListingCreatedAt,
            int expectedProductImageCount,
            int expectedListingImageCount,
            int selectionOrder,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.product = product;
        this.oldListing = oldListing;
        this.oldExternalListingId = oldExternalListingId;
        this.oldShopSlug = oldShopSlug;
        this.oldProductSlug = oldProductSlug;
        this.oldPublicUrl = oldPublicUrl;
        this.productTitle = productTitle;
        this.selectedExternalCreatedAt = selectedExternalCreatedAt;
        this.selectedListingCreatedAt = selectedListingCreatedAt;
        this.expectedProductImageCount = expectedProductImageCount;
        this.expectedListingImageCount = expectedListingImageCount;
        this.status = YagaRefreshJobStatus.SELECTED;
        this.selectionOrder = selectionOrder;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
