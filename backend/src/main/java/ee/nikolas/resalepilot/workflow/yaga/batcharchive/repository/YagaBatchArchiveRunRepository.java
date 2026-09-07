package ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository;

import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface YagaBatchArchiveRunRepository
        extends JpaRepository<YagaBatchArchiveRun, UUID> {

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.marketplaceListing"
    })
    @Query("""
            select run
            from YagaBatchArchiveRun run
            where run.id = :id
            """)
    Optional<YagaBatchArchiveRun> findWithJobsById(UUID id);

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.marketplaceListing"
    })
    Optional<YagaBatchArchiveRun> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select run
            from YagaBatchArchiveRun run
            where run.id = :id
            """)
    Optional<YagaBatchArchiveRun> findByIdForUpdate(UUID id);
}
