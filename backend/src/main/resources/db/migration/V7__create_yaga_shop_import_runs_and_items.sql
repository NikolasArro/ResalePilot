CREATE TABLE yaga_shop_import_runs (
    id UUID PRIMARY KEY,
    shop_slug VARCHAR(150) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requested_max_items INTEGER NOT NULL,
    selected_item_count INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    existing_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_yaga_shop_import_runs_shop_idempotency
        UNIQUE (shop_slug, idempotency_key),
    CONSTRAINT chk_yaga_shop_import_runs_status
        CHECK (status IN (
            'PREPARING',
            'AWAITING_CONFIRMATION',
            'IMPORTING',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'CANCELLED'
        )),
    CONSTRAINT chk_yaga_shop_import_runs_requested_max_items
        CHECK (requested_max_items >= 1),
    CONSTRAINT chk_yaga_shop_import_runs_selected_item_count
        CHECK (selected_item_count >= 0),
    CONSTRAINT chk_yaga_shop_import_runs_imported_count
        CHECK (imported_count >= 0),
    CONSTRAINT chk_yaga_shop_import_runs_existing_count
        CHECK (existing_count >= 0),
    CONSTRAINT chk_yaga_shop_import_runs_skipped_count
        CHECK (skipped_count >= 0),
    CONSTRAINT chk_yaga_shop_import_runs_failed_count
        CHECK (failed_count >= 0)
);

CREATE INDEX idx_yaga_shop_import_runs_status
    ON yaga_shop_import_runs (status);

CREATE INDEX idx_yaga_shop_import_runs_shop_slug
    ON yaga_shop_import_runs (shop_slug);

CREATE TABLE yaga_shop_import_items (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    selection_order INTEGER NOT NULL,
    external_listing_id VARCHAR(100),
    shop_slug VARCHAR(150) NOT NULL,
    product_slug VARCHAR(150) NOT NULL,
    public_url TEXT NOT NULL,
    discovered_status VARCHAR(50) NOT NULL,
    external_created_at TIMESTAMPTZ,
    expected_image_count INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    product_id BIGINT,
    marketplace_listing_id BIGINT,
    last_error_code VARCHAR(100),
    last_safe_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_yaga_shop_import_items_run
        FOREIGN KEY (run_id)
        REFERENCES yaga_shop_import_runs (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_yaga_shop_import_items_product
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_yaga_shop_import_items_listing
        FOREIGN KEY (marketplace_listing_id)
        REFERENCES marketplace_listings (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_yaga_shop_import_items_run_order
        UNIQUE (run_id, selection_order),
    CONSTRAINT chk_yaga_shop_import_items_status
        CHECK (status IN (
            'SELECTED',
            'IMPORTING',
            'IMPORTED',
            'ALREADY_EXISTS',
            'SKIPPED_NOT_ACTIVE',
            'SKIPPED_INVALID_DATA',
            'FAILED'
        )),
    CONSTRAINT chk_yaga_shop_import_items_selection_order
        CHECK (selection_order >= 0),
    CONSTRAINT chk_yaga_shop_import_items_expected_image_count
        CHECK (expected_image_count >= 0)
);

CREATE INDEX idx_yaga_shop_import_items_run_id
    ON yaga_shop_import_items (run_id);

CREATE INDEX idx_yaga_shop_import_items_status
    ON yaga_shop_import_items (status);

CREATE INDEX idx_yaga_shop_import_items_external_listing_id
    ON yaga_shop_import_items (external_listing_id);

CREATE INDEX idx_yaga_shop_import_items_shop_product_slug
    ON yaga_shop_import_items (shop_slug, product_slug);

CREATE INDEX idx_yaga_shop_import_items_product_id
    ON yaga_shop_import_items (product_id);

CREATE INDEX idx_yaga_shop_import_items_marketplace_listing_id
    ON yaga_shop_import_items (marketplace_listing_id);
