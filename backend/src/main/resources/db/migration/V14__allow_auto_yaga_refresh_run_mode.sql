ALTER TABLE yaga_refresh_runs
    DROP CONSTRAINT chk_yaga_refresh_runs_mode;

ALTER TABLE yaga_refresh_runs
    ADD CONSTRAINT chk_yaga_refresh_runs_mode
        CHECK (mode IN ('DRY_RUN', 'MANUAL', 'AUTO'));
