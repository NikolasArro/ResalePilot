ALTER TABLE marketplace_listings
    DROP CONSTRAINT chk_marketplace_listings_status;

ALTER TABLE marketplace_listings
    ADD CONSTRAINT chk_marketplace_listings_status
        CHECK (
            status IN (
                       'PUBLISHED',
                       'SOLD',
                       'HIDDEN',
                       'DELETED',
                       'UNAVAILABLE',
                       'UNKNOWN'
                )
            );
