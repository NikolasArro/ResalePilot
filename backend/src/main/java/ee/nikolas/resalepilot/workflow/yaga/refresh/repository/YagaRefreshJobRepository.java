package ee.nikolas.resalepilot.workflow.yaga.refresh.repository;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface YagaRefreshJobRepository
        extends JpaRepository<YagaRefreshJob, UUID> {

    boolean existsByProductIdAndStatusIn(
            Long productId,
            Collection<YagaRefreshJobStatus> statuses
    );

    @Query(
            value = """
                    select
                        listing.id as "listingId",
                        product.id as "productId",
                        product.sku as "sku",
                        product.title as "title",
                        listing.external_listing_id as "externalListingId",
                        listing.shop_slug as "shopSlug",
                        listing.product_slug as "productSlug",
                        listing.external_url as "externalUrl",
                        listing.external_created_at as "externalCreatedAt",
                        listing.created_at as "listingCreatedAt",
                        coalesce(
                            listing.external_created_at,
                            listing.created_at
                        ) as "orderingTimestamp",
                        (
                            select count(*)
                            from product_images product_image
                            where product_image.product_id = product.id
                        ) as "productImageCount",
                        (
                            select count(*)
                            from marketplace_listing_images listing_image
                            where listing_image.marketplace_listing_id =
                                    listing.id
                        ) as "listingImageCount"
                    from marketplace_listings listing
                    join products product
                        on product.id = listing.product_id
                    where listing.marketplace = 'YAGA'
                        and listing.status = 'PUBLISHED'
                        and listing.is_current = true
                        and listing.hidden_at is null
                        and listing.deleted_at is null
                        and listing.external_listing_id is not null
                        and listing.external_listing_id <> ''
                        and listing.shop_slug is not null
                        and listing.shop_slug <> ''
                        and listing.product_slug is not null
                        and listing.product_slug <> ''
                        and product.status <> 'ARCHIVED'
                        and exists (
                            select 1
                            from product_images product_image
                            where product_image.product_id = product.id
                        )
                        and exists (
                            select 1
                            from marketplace_listing_images listing_image
                            where listing_image.marketplace_listing_id =
                                    listing.id
                        )
                        and not exists (
                            select 1
                            from marketplace_listing_images listing_image
                            where listing_image.marketplace_listing_id =
                                    listing.id
                                and listing_image.product_image_id is null
                        )
                        and (
                            select count(*)
                            from marketplace_listing_images listing_image
                            where listing_image.marketplace_listing_id =
                                    listing.id
                        ) = (
                            select count(*)
                            from marketplace_listing_images listing_image
                            where listing_image.marketplace_listing_id =
                                    listing.id
                                and listing_image.product_image_id is not null
                        )
                        and not exists (
                            select 1
                            from yaga_refresh_jobs active_job
                            where active_job.product_id = product.id
                                and active_job.status in (
                                    'SELECTED',
                                    'PUBLISHING',
                                    'NEW_LISTING_CONFIRMED',
                                    'HIDING_OLD',
                                    'RESULT_UNKNOWN'
                                )
                        )
                    order by
                        listing.external_created_at asc nulls last,
                        listing.created_at asc,
                        listing.id
                    limit :limit
                    for update of listing skip locked
                    """,
            nativeQuery = true
    )
    List<YagaRefreshCandidateRow> selectCandidatesForUpdate(
            int limit
    );
}
