package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
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
@Table(name = "yaga_refresh_runs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YagaRefreshRun {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private YagaRefreshTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private YagaRefreshRunMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private YagaRefreshRunStatus status;

    @Column(name = "requested_batch_size", nullable = false)
    private int requestedBatchSize;

    @Column(name = "selected_job_count", nullable = false)
    private int selectedJobCount;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
    private List<YagaRefreshJob> jobs = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    public YagaRefreshRun(
            YagaRefreshTriggerType triggerType,
            YagaRefreshRunMode mode,
            int requestedBatchSize,
            String idempotencyKey,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.triggerType = triggerType;
        this.mode = mode;
        this.status = YagaRefreshRunStatus.CREATED;
        this.requestedBatchSize = requestedBatchSize;
        this.idempotencyKey = idempotencyKey;
        this.startedAt = now;
        this.createdAt = now;
    }

    public void addJob(YagaRefreshJob job) {
        job.setRun(this);
        jobs.add(job);
    }
}
