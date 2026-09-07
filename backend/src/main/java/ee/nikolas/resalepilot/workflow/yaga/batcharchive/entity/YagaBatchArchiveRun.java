package ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity;

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
        name = "yaga_batch_archive_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_yaga_batch_archive_runs_idempotency",
                columnNames = "idempotency_key"
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaBatchArchiveRun {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaBatchArchiveRunStatus status;

    @Column(name = "requested_max_listings", nullable = false)
    private int requestedMaxListings;

    @Column(name = "selected_job_count", nullable = false)
    private int selectedJobCount;

    @Column(name = "archived_listing_count", nullable = false)
    private int archivedListingCount;

    @Column(name = "already_archived_listing_count", nullable = false)
    private int alreadyArchivedListingCount;

    @Column(name = "failed_listing_count", nullable = false)
    private int failedListingCount;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

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
    private List<YagaBatchArchiveJob> jobs = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaBatchArchiveRun(
            int requestedMaxListings,
            String idempotencyKey,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.status = YagaBatchArchiveRunStatus.PREPARING;
        this.requestedMaxListings = requestedMaxListings;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    public void addJob(YagaBatchArchiveJob job) {
        job.setRun(this);
        jobs.add(job);
    }
}
