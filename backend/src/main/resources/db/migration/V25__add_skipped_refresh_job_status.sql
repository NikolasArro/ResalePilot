ALTER TABLE yaga_refresh_jobs DROP CONSTRAINT chk_yaga_refresh_jobs_status;
ALTER TABLE yaga_refresh_jobs ADD CONSTRAINT chk_yaga_refresh_jobs_status
    CHECK (status IN ('SELECTED', 'PUBLISHING', 'NEW_LISTING_CONFIRMED',
                     'HIDING_OLD', 'COMPLETED', 'RESULT_UNKNOWN',
                     'DRY_RUN_COMPLETED', 'SKIPPED', 'FAILED'));
