package ee.nikolas.resalepilot.workflow.yaga.shopimport.repository;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface YagaShopImportRunRepository
        extends JpaRepository<YagaShopImportRun, UUID> {

    @EntityGraph(attributePaths = {
            "items",
            "items.product",
            "items.marketplaceListing"
    })
    Optional<YagaShopImportRun> findByShopSlugAndIdempotencyKey(
            String shopSlug,
            String idempotencyKey
    );

    @Query("""
            select distinct run
            from YagaShopImportRun run
            left join fetch run.items items
            left join fetch items.product
            left join fetch items.marketplaceListing
            where run.id = :id
            """)
    Optional<YagaShopImportRun> findWithItemsById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select run
            from YagaShopImportRun run
            where run.id = :id
            """)
    Optional<YagaShopImportRun> findByIdForUpdate(UUID id);
}
