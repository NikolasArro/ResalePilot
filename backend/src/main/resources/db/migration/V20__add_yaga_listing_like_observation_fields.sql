alter table marketplace_listings
    add column like_count integer,
    add column like_count_observed_at timestamp with time zone,
    add column last_like_increase_observed_at timestamp with time zone;

alter table marketplace_listings
    add constraint chk_marketplace_listings_like_count_non_negative
        check (like_count is null or like_count >= 0);
