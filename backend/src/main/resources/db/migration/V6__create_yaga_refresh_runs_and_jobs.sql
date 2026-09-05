CREATE TABLE yaga_refresh_runs (
    id UUID PRIMARY KEY,
    trigger_type VARCHAR(30) NOT NULL,
    mode VARCHAR(30) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requested_batch_size INTEGER NOT NULL,
    selected_job_count INTEGER NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_yaga_refresh_runs_trigger_type
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT chk_yaga_refresh_runs_mode
        CHECK (mode IN ('DRY_RUN')),
    CONSTRAINT chk_yaga_refresh_runs_status
        CHECK (status IN ('CREATED', 'SELECTING', 'DRY_RUN_COMPLETED', 'FAILED')),
    CONSTRAINT chk_yaga_refresh_runs_requested_batch_size
        CHECK (requested_batch_size >= 1),
    CONSTRAINT chk_yaga_refresh_runs_selected_job_count
        CHECK (selected_job_count >= 0)
);

CREATE UNIQUE INDEX uq_yaga_refresh_runs_idempotency_key
    ON yaga_refresh_runs (trigger_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_yaga_refresh_runs_status
    ON yaga_refresh_runs (status);

CREATE TABLE yaga_refresh_jobs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    product_id BIGINT NOT NULL,
    old_listing_id BIGINT NOT NULL,
    new_listing_id BIGINT,
    status VARCHAR(50) NOT NULL,
    selection_order INTEGER NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    new_product_url TEXT,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_yaga_refresh_jobs_run
        FOREIGN KEY (run_id)
        REFERENCES yaga_refresh_runs (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_refresh_jobs_product
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_refresh_jobs_old_listing
        FOREIGN KEY (old_listing_id)
        REFERENCES marketplace_listings (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_refresh_jobs_new_listing
        FOREIGN KEY (new_listing_id)
        REFERENCES marketplace_listings (id)
        ON DELETE SET NULL,
    CONSTRAINT chk_yaga_refresh_jobs_status
        CHECK (status IN ('SELECTED', 'DRY_RUN_COMPLETED', 'FAILED')),
    CONSTRAINT chk_yaga_refresh_jobs_selection_order
        CHECK (selection_order >= 0),
    CONSTRAINT chk_yaga_refresh_jobs_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_yaga_refresh_jobs_run_id
    ON yaga_refresh_jobs (run_id);

CREATE INDEX idx_yaga_refresh_jobs_product_id
    ON yaga_refresh_jobs (product_id);

CREATE INDEX idx_yaga_refresh_jobs_old_listing_id
    ON yaga_refresh_jobs (old_listing_id);

CREATE INDEX idx_yaga_refresh_jobs_status
    ON yaga_refresh_jobs (status);

CREATE INDEX idx_yaga_refresh_jobs_next_retry_at
    ON yaga_refresh_jobs (next_retry_at);

CREATE UNIQUE INDEX uq_yaga_refresh_jobs_active_product
    ON yaga_refresh_jobs (product_id)
    WHERE status IN ('SELECTED');

CREATE INDEX idx_marketplace_listings_yaga_refresh_candidates
    ON marketplace_listings (
        marketplace,
        status,
        is_current,
        external_created_at,
        created_at,
        id
    );
