package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "yaga_account_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_yaga_refresh_runs_account")
    )
    private YagaAccount yagaAccount;

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
            YagaAccount yagaAccount,
            YagaRefreshTriggerType triggerType,
            YagaRefreshRunMode mode,
            int requestedBatchSize,
            String idempotencyKey,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.yagaAccount = yagaAccount;
        this.triggerType = triggerType;
        this.mode = mode;
        this.status = YagaRefreshRunStatus.CREATED;
        this.requestedBatchSize = requestedBatchSize;
        this.idempotencyKey = idempotencyKey;
        this.startedAt = now;
        this.createdAt = now;
    }

    public YagaRefreshRun(
            YagaRefreshTriggerType triggerType,
            YagaRefreshRunMode mode,
            int requestedBatchSize,
            String idempotencyKey,
            Instant now
    ) {
        this(
                new YagaAccount(
                        "Default Yaga account",
                        "nik-ar",
                        "../playwright/.auth/yaga-state.json",
                        10
                ),
                triggerType,
                mode,
                requestedBatchSize,
                idempotencyKey,
                now
        );
        this.yagaAccount.setId(1L);
    }

    public void addJob(YagaRefreshJob job) {
        job.setRun(this);
        jobs.add(job);
    }
}
