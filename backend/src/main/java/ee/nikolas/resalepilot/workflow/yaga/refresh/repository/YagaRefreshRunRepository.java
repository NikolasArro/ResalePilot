package ee.nikolas.resalepilot.workflow.yaga.refresh.repository;

import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface YagaRefreshRunRepository
        extends JpaRepository<YagaRefreshRun, UUID> {

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.oldListing"
    })
    Optional<YagaRefreshRun> findWithJobsById(UUID id);

    @EntityGraph(attributePaths = {
            "jobs",
            "jobs.product",
            "jobs.oldListing"
    })
    Optional<YagaRefreshRun> findWithJobsByTriggerTypeAndIdempotencyKey(
            YagaRefreshTriggerType triggerType,
            String idempotencyKey
    );
}
