package ee.nikolas.resalepilot.workflow.yaga.shopimport.repository;

import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItem;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.entity.YagaShopImportItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface YagaShopImportItemRepository
        extends JpaRepository<YagaShopImportItem, UUID> {

    @EntityGraph(attributePaths = {"product", "marketplaceListing"})
    List<YagaShopImportItem> findAllByRunIdOrderBySelectionOrderAsc(
            UUID runId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
            from YagaShopImportItem item
            where item.run.id = :runId and item.status in :statuses
            order by item.selectionOrder asc
            """)
    List<YagaShopImportItem> findPendingForUpdate(
            UUID runId,
            List<YagaShopImportItemStatus> statuses
    );
}
