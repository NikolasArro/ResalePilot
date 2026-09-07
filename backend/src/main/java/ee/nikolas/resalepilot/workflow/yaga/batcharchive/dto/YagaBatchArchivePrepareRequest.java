package ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto;

import jakarta.validation.constraints.Size;

public record YagaBatchArchivePrepareRequest(
        Integer maxListings,
        @Size(max = 120) String idempotencyKey
) {
}
