ALTER TABLE yaga_refresh_jobs
    ADD COLUMN publication_preparation_id UUID,
    ADD COLUMN publication_status VARCHAR(50),
    ADD COLUMN new_external_listing_id VARCHAR(100),
    ADD COLUMN new_shop_slug VARCHAR(150),
    ADD COLUMN new_product_slug VARCHAR(150),
    ADD COLUMN publication_prepared_at TIMESTAMPTZ,
    ADD COLUMN publication_confirmed_at TIMESTAMPTZ;

CREATE INDEX idx_yaga_refresh_jobs_publication_preparation_id
    ON yaga_refresh_jobs (publication_preparation_id);

CREATE INDEX idx_yaga_refresh_jobs_new_listing_id
    ON yaga_refresh_jobs (new_listing_id);
