CREATE TABLE yaga_batch_archive_runs (
    id UUID PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    requested_max_listings INTEGER NOT NULL,
    selected_job_count INTEGER NOT NULL DEFAULT 0,
    archived_listing_count INTEGER NOT NULL DEFAULT 0,
    already_archived_listing_count INTEGER NOT NULL DEFAULT 0,
    failed_listing_count INTEGER NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_yaga_batch_archive_runs_idempotency
        UNIQUE (idempotency_key),
    CONSTRAINT chk_yaga_batch_archive_runs_status
        CHECK (status IN (
            'PREPARING',
            'AWAITING_CONFIRMATION',
            'ARCHIVING',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'CANCELLED'
        )),
    CONSTRAINT chk_yaga_batch_archive_runs_requested_max_listings
        CHECK (requested_max_listings >= 1),
    CONSTRAINT chk_yaga_batch_archive_runs_selected_job_count
        CHECK (selected_job_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_runs_archived_listing_count
        CHECK (archived_listing_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_runs_already_archived_listing_count
        CHECK (already_archived_listing_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_runs_failed_listing_count
        CHECK (failed_listing_count >= 0)
);

CREATE INDEX idx_yaga_batch_archive_runs_status
    ON yaga_batch_archive_runs (status);

CREATE TABLE yaga_batch_archive_jobs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    product_id BIGINT NOT NULL,
    marketplace_listing_id BIGINT NOT NULL,
    selection_order INTEGER NOT NULL,
    expected_image_count INTEGER NOT NULL,
    initially_linked_image_count INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    archived_image_count INTEGER NOT NULL DEFAULT 0,
    already_linked_image_count INTEGER NOT NULL DEFAULT 0,
    failed_image_count INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_yaga_batch_archive_jobs_run
        FOREIGN KEY (run_id)
        REFERENCES yaga_batch_archive_runs (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_batch_archive_jobs_product
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_batch_archive_jobs_listing
        FOREIGN KEY (marketplace_listing_id)
        REFERENCES marketplace_listings (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_yaga_batch_archive_jobs_run_order
        UNIQUE (run_id, selection_order),
    CONSTRAINT chk_yaga_batch_archive_jobs_status
        CHECK (status IN (
            'SELECTED',
            'ARCHIVING',
            'ARCHIVED',
            'ALREADY_ARCHIVED',
            'FAILED'
        )),
    CONSTRAINT chk_yaga_batch_archive_jobs_selection_order
        CHECK (selection_order >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_expected_image_count
        CHECK (expected_image_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_initially_linked_image_count
        CHECK (initially_linked_image_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_archived_image_count
        CHECK (archived_image_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_already_linked_image_count
        CHECK (already_linked_image_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_failed_image_count
        CHECK (failed_image_count >= 0),
    CONSTRAINT chk_yaga_batch_archive_jobs_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_yaga_batch_archive_jobs_run_id
    ON yaga_batch_archive_jobs (run_id);

CREATE INDEX idx_yaga_batch_archive_jobs_listing_id
    ON yaga_batch_archive_jobs (marketplace_listing_id);

CREATE INDEX idx_yaga_batch_archive_jobs_status
    ON yaga_batch_archive_jobs (status);

CREATE UNIQUE INDEX uq_yaga_batch_archive_active_listing
    ON yaga_batch_archive_jobs (marketplace_listing_id)
    WHERE status IN ('SELECTED', 'ARCHIVING');
