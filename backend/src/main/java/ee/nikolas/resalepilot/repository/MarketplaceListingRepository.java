package ee.nikolas.resalepilot.repository;

import ee.nikolas.resalepilot.entity.Marketplace;
import ee.nikolas.resalepilot.entity.MarketplaceListing;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
