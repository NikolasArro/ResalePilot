ALTER TABLE yaga_refresh_jobs
    ADD COLUMN hide_preparation_id UUID,
    ADD COLUMN hide_status VARCHAR(50) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN hide_prepared_at TIMESTAMPTZ,
    ADD COLUMN hide_confirm_started_at TIMESTAMPTZ,
    ADD COLUMN hide_confirmed_at TIMESTAMPTZ;

ALTER TABLE yaga_refresh_jobs
    ADD CONSTRAINT chk_yaga_refresh_jobs_hide_status
        CHECK (hide_status IN (
            'NOT_STARTED',
            'AWAITING_CONFIRMATION',
            'CONFIRMING',
            'RESULT_UNKNOWN',
            'HIDDEN',
            'EXPIRED',
            'CANCELLED'
        ));

ALTER TABLE yaga_refresh_jobs
    ALTER COLUMN hide_status DROP DEFAULT;

CREATE INDEX idx_yaga_refresh_jobs_hide_preparation_id
    ON yaga_refresh_jobs (hide_preparation_id);

DROP INDEX uq_yaga_refresh_jobs_active_product;

CREATE UNIQUE INDEX uq_yaga_refresh_jobs_active_product
    ON yaga_refresh_jobs (product_id)
    WHERE status IN (
        'SELECTED',
        'PUBLISHING',
        'NEW_LISTING_CONFIRMED',
        'HIDING_OLD',
        'RESULT_UNKNOWN'
    );
