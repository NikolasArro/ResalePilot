package ee.nikolas.resalepilot.workflow.yaga.shopimport.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "yaga_shop_import_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_yaga_shop_import_runs_shop_idempotency",
                columnNames = {"shop_slug", "idempotency_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaShopImportRun {

    @Id
    private UUID id;

    @Column(name = "shop_slug", nullable = false, length = 150)
    private String shopSlug;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaShopImportRunStatus status;

    @Column(name = "requested_max_items", nullable = false)
    private int requestedMaxItems;

    @Column(name = "selected_item_count", nullable = false)
    private int selectedItemCount;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "existing_count", nullable = false)
    private int existingCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_safe_error_message", columnDefinition = "TEXT")
    private String lastSafeErrorMessage;

    @OneToMany(
            mappedBy = "run",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("selectionOrder ASC")
    private List<YagaShopImportItem> items = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaShopImportRun(
            String shopSlug,
            String idempotencyKey,
            int requestedMaxItems,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.shopSlug = shopSlug;
        this.idempotencyKey = idempotencyKey;
        this.status = YagaShopImportRunStatus.PREPARING;
        this.requestedMaxItems = requestedMaxItems;
        this.createdAt = now;
        this.selectedItemCount = 0;
        this.importedCount = 0;
        this.existingCount = 0;
        this.skippedCount = 0;
        this.failedCount = 0;
    }

    public void addItem(YagaShopImportItem item) {
        item.setRun(this);
        items.add(item);
    }
}
