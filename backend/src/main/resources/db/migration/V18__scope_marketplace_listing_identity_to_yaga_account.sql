ALTER TABLE marketplace_listings
    DROP CONSTRAINT uq_marketplace_external_listing;

ALTER TABLE marketplace_listings
    ADD CONSTRAINT uq_marketplace_account_external_listing
        UNIQUE (yaga_account_id, marketplace, external_listing_id);

CREATE UNIQUE INDEX uq_marketplace_account_shop_product_slug
    ON marketplace_listings (
        yaga_account_id,
        marketplace,
        shop_slug,
        product_slug
    )
    WHERE shop_slug IS NOT NULL
      AND product_slug IS NOT NULL;
