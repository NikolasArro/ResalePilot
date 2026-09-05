package ee.nikolas.resalepilot.marketplace.repository;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MarketplaceListingRepository
        extends JpaRepository<MarketplaceListing, Long> {

    Optional<MarketplaceListing>
    findByMarketplaceAndExternalListingId(
            Marketplace marketplace,
            String externalListingId
    );

    Optional<MarketplaceListing>
    findByProductIdAndMarketplaceAndCurrentTrue(
            Long productId,
            Marketplace marketplace
    );

    Optional<MarketplaceListing>
    findByMarketplaceAndShopSlugAndProductSlug(
            Marketplace marketplace,
            String shopSlug,
            String productSlug
    );

    List<MarketplaceListing>
    findAllByProductIdAndMarketplaceAndStatus(
            Long productId,
            Marketplace marketplace,
            ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus status
    );

    @Query("""
            select distinct listing
            from MarketplaceListing listing
            join fetch listing.product
            left join fetch listing.images images
            left join fetch images.productImage
            where listing.id = :id
            """)
    Optional<MarketplaceListing> findByIdWithImages(
            Long id
    );

    @Query("""
            select distinct listing
            from MarketplaceListing listing
            join fetch listing.product
            left join fetch listing.categories
            where listing.id = :id
            """)
    Optional<MarketplaceListing> findByIdWithCategories(
            Long id
    );

    @Query("""
            select distinct listing
            from MarketplaceListing listing
            join fetch listing.product
            left join fetch listing.images images
            left join fetch images.productImage productImage
            where listing.id = :id
            """)
    Optional<MarketplaceListing> findByIdWithImagesAndProductImages(
            Long id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select listing
            from MarketplaceListing listing
            join fetch listing.product
            where listing.id = :id
            """)
    Optional<MarketplaceListing> findByIdForUpdate(
            Long id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct listing
            from MarketplaceListing listing
            join fetch listing.product
            left join fetch listing.images images
            left join fetch images.productImage
            where listing.id = :id
            """)
    Optional<MarketplaceListing> findByIdWithImagesForUpdate(
            Long id
    );

    boolean existsByMarketplaceAndExternalListingId(
            Marketplace marketplace,
            String externalListingId
    );
}
