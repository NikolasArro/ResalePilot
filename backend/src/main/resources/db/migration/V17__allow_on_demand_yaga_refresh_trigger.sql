ALTER TABLE yaga_refresh_runs
    DROP CONSTRAINT chk_yaga_refresh_runs_trigger_type;

ALTER TABLE yaga_refresh_runs
    ADD CONSTRAINT chk_yaga_refresh_runs_trigger_type
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'ON_DEMAND'));
