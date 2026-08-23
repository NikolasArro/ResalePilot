CREATE TABLE products
(
    id             BIGSERIAL PRIMARY KEY,
    sku            VARCHAR(30)              NOT NULL UNIQUE,
    title          VARCHAR(150)             NOT NULL,
    description    TEXT,
    category       VARCHAR(100),
    brand          VARCHAR(100),
    size           VARCHAR(50),
    condition      VARCHAR(30),
    color          VARCHAR(50),
    purchase_price NUMERIC(10, 2),
    asking_price   NUMERIC(10, 2),
    minimum_price  NUMERIC(10, 2),
    status         VARCHAR(30)              NOT NULL DEFAULT 'DRAFT',
    acquired_at    DATE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version        BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT chk_products_title_not_blank
        CHECK (BTRIM(title) <> ''),

    CONSTRAINT chk_products_condition
        CHECK (condition IS NULL OR condition IN (
            'NEW_WITH_TAGS',
            'NEW_WITHOUT_TAGS',
            'VERY_GOOD',
            'GOOD',
            'SATISFACTORY'
        )),

    CONSTRAINT chk_products_status
        CHECK (status IN (
            'DRAFT',
            'READY',
            'SCHEDULED',
            'LISTED',
            'SOLD',
            'ARCHIVED'
        )),

    CONSTRAINT chk_products_purchase_price
        CHECK (purchase_price IS NULL OR purchase_price >= 0),

    CONSTRAINT chk_products_asking_price
        CHECK (asking_price IS NULL OR asking_price > 0),

    CONSTRAINT chk_products_minimum_price
        CHECK (minimum_price IS NULL OR minimum_price >= 0),

    CONSTRAINT chk_products_price_range
        CHECK (
            minimum_price IS NULL
            OR asking_price IS NULL
            OR minimum_price <= asking_price
        )
);

CREATE INDEX idx_products_status
    ON products (status);

CREATE INDEX idx_products_category
    ON products (category);

CREATE INDEX idx_products_created_at
    ON products (created_at DESC);
