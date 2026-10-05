ALTER TABLE marketplace_listings
    ADD COLUMN delivery_omniva_enabled boolean,
    ADD COLUMN delivery_omniva_size varchar(32),
    ADD COLUMN delivery_dpd_enabled boolean,
    ADD COLUMN delivery_dpd_size varchar(32),
    ADD COLUMN delivery_smartpost_enabled boolean,
    ADD COLUMN delivery_smartpost_size varchar(32),
    ADD COLUMN delivery_pickup_enabled boolean,
    ADD COLUMN delivery_agreement_enabled boolean,
    ADD COLUMN delivery_bundling_enabled boolean;

ALTER TABLE marketplace_listings ADD CONSTRAINT ck_yaga_delivery_complete CHECK (
    (delivery_omniva_enabled IS NULL AND delivery_omniva_size IS NULL
        AND delivery_dpd_enabled IS NULL AND delivery_dpd_size IS NULL
        AND delivery_smartpost_enabled IS NULL AND delivery_smartpost_size IS NULL
        AND delivery_pickup_enabled IS NULL AND delivery_agreement_enabled IS NULL
        AND delivery_bundling_enabled IS NULL)
    OR
    (delivery_omniva_enabled IS NOT NULL AND delivery_dpd_enabled IS NOT NULL
        AND delivery_smartpost_enabled IS NOT NULL AND delivery_pickup_enabled IS NOT NULL
        AND delivery_agreement_enabled IS NOT NULL AND delivery_bundling_enabled IS NOT NULL
        AND (NOT delivery_omniva_enabled OR delivery_omniva_size IS NOT NULL)
        AND (NOT delivery_dpd_enabled OR delivery_dpd_size IS NOT NULL)
        AND (NOT delivery_smartpost_enabled OR delivery_smartpost_size IS NOT NULL))
);
