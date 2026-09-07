package ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity;

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
        name = "yaga_batch_archive_jobs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_yaga_batch_archive_jobs_run_order",
                columnNames = {"run_id", "selection_order"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaBatchArchiveJob {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "run_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_batch_archive_jobs_run")
    )
    private YagaBatchArchiveRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_batch_archive_jobs_product")
    )
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "marketplace_listing_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_batch_archive_jobs_listing")
    )
    private MarketplaceListing marketplaceListing;

    @Column(name = "selection_order", nullable = false)
    private int selectionOrder;

    @Column(name = "expected_image_count", nullable = false)
    private int expectedImageCount;

    @Column(name = "initially_linked_image_count", nullable = false)
    private int initiallyLinkedImageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaBatchArchiveJobStatus status;

    @Column(name = "archived_image_count", nullable = false)
    private int archivedImageCount;

    @Column(name = "already_linked_image_count", nullable = false)
    private int alreadyLinkedImageCount;

    @Column(name = "failed_image_count", nullable = false)
    private int failedImageCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

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

    public YagaBatchArchiveJob(
            Product product,
            MarketplaceListing marketplaceListing,
            int selectionOrder,
            int expectedImageCount,
            int initiallyLinkedImageCount,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.product = product;
        this.marketplaceListing = marketplaceListing;
        this.selectionOrder = selectionOrder;
        this.expectedImageCount = expectedImageCount;
        this.initiallyLinkedImageCount = initiallyLinkedImageCount;
        this.status = YagaBatchArchiveJobStatus.SELECTED;
        this.createdAt = now;
    }
}
