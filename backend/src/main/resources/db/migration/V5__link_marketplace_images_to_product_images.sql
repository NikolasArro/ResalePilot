ALTER TABLE marketplace_listing_images
    ADD COLUMN product_image_id BIGINT;

ALTER TABLE marketplace_listing_images
    ADD CONSTRAINT fk_listing_images_product_image
        FOREIGN KEY (product_image_id)
            REFERENCES product_images (id)
            ON DELETE SET NULL;

CREATE INDEX idx_listing_images_product_image
    ON marketplace_listing_images (product_image_id);
