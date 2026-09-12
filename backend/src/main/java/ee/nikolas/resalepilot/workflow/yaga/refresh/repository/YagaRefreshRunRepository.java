package ee.nikolas.resalepilot.workflow.yaga.refresh.repository;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface YagaRefreshRunRepository
        extends JpaRepository<YagaRefreshRun, UUID> {

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.oldListing",
            "jobs.newListing"
    })
    Optional<YagaRefreshRun> findWithJobsById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.oldListing",
            "jobs.newListing"
    })
    @Query("""
            select run
            from YagaRefreshRun run
            where run.id = :id
            """)
    Optional<YagaRefreshRun> findForUpdateWithJobsById(UUID id);

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.oldListing",
            "jobs.newListing"
    })
    Optional<YagaRefreshRun> findWithJobsByTriggerTypeAndIdempotencyKey(
            YagaRefreshTriggerType triggerType,
            String idempotencyKey
    );
}
