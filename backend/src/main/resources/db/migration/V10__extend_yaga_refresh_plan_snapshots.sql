ALTER TABLE yaga_refresh_runs
    DROP CONSTRAINT chk_yaga_refresh_runs_mode;

ALTER TABLE yaga_refresh_runs
    ADD CONSTRAINT chk_yaga_refresh_runs_mode
        CHECK (mode IN ('DRY_RUN', 'MANUAL'));

ALTER TABLE yaga_refresh_runs
    DROP CONSTRAINT chk_yaga_refresh_runs_status;

ALTER TABLE yaga_refresh_runs
    ADD CONSTRAINT chk_yaga_refresh_runs_status
        CHECK (status IN (
            'CREATED',
            'SELECTING',
            'DRY_RUN_COMPLETED',
            'PREPARING',
            'AWAITING_CONFIRMATION',
            'PROCESSING',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'CANCELLED'
        ));

ALTER TABLE yaga_refresh_jobs
    DROP CONSTRAINT chk_yaga_refresh_jobs_status;

ALTER TABLE yaga_refresh_jobs
    ADD CONSTRAINT chk_yaga_refresh_jobs_status
        CHECK (status IN (
            'SELECTED',
            'DRY_RUN_COMPLETED',
            'PUBLISHING',
            'NEW_LISTING_CONFIRMED',
            'HIDING_OLD',
            'COMPLETED',
            'FAILED',
            'RESULT_UNKNOWN'
        ));

ALTER TABLE yaga_refresh_jobs
    ADD COLUMN old_external_listing_id VARCHAR(100),
    ADD COLUMN old_shop_slug VARCHAR(150),
    ADD COLUMN old_product_slug VARCHAR(150),
    ADD COLUMN old_public_url TEXT,
    ADD COLUMN product_title VARCHAR(150),
    ADD COLUMN selected_external_created_at TIMESTAMPTZ,
    ADD COLUMN selected_listing_created_at TIMESTAMPTZ,
    ADD COLUMN expected_product_image_count INTEGER,
    ADD COLUMN expected_listing_image_count INTEGER;

ALTER TABLE yaga_refresh_jobs
    ADD CONSTRAINT chk_yaga_refresh_jobs_expected_product_image_count
        CHECK (
            expected_product_image_count IS NULL
            OR expected_product_image_count >= 0
        ),
    ADD CONSTRAINT chk_yaga_refresh_jobs_expected_listing_image_count
        CHECK (
            expected_listing_image_count IS NULL
            OR expected_listing_image_count >= 0
        );

DROP INDEX uq_yaga_refresh_jobs_active_product;

CREATE UNIQUE INDEX uq_yaga_refresh_jobs_active_product
    ON yaga_refresh_jobs (product_id)
    WHERE status IN (
        'SELECTED',
        'PUBLISHING',
        'NEW_LISTING_CONFIRMED',
        'HIDING_OLD'
    );
