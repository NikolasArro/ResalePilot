package ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface YagaBatchArchiveJobRepository
        extends JpaRepository<YagaBatchArchiveJob, UUID> {

    List<YagaBatchArchiveJob> findAllByRunIdOrderBySelectionOrderAsc(
            UUID runId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job
            from YagaBatchArchiveJob job
            join fetch job.product
            join fetch job.marketplaceListing
            where job.id = :id
            """)
    Optional<YagaBatchArchiveJob> findByIdForUpdate(UUID id);

    @Query("""
            select listing
            from MarketplaceListing listing
            join fetch listing.product product
            where listing.marketplace = :marketplace
              and listing.status = :status
              and listing.current = true
              and product.status <> :archivedProductStatus
              and exists (
                  select 1
                  from MarketplaceListingImage anyImage
                  where anyImage.marketplaceListing = listing
              )
              and exists (
                  select 1
                  from MarketplaceListingImage missingImage
                  where missingImage.marketplaceListing = listing
                    and missingImage.productImage is null
              )
              and not exists (
                  select 1
                  from YagaBatchArchiveJob activeJob
                  where activeJob.marketplaceListing = listing
                    and activeJob.status in (
                        ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJobStatus.SELECTED,
                        ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJobStatus.ARCHIVING
                    )
            )
            order by coalesce(listing.externalCreatedAt, listing.createdAt) asc,
                     listing.id asc
            """)
    List<MarketplaceListing> findEligibleListings(
            Marketplace marketplace,
            MarketplaceListingStatus status,
            ProductStatus archivedProductStatus,
            Pageable pageable
    );
}
