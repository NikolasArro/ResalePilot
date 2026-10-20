package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record YagaStrandedJobRepairRequest(
        @NotNull List<@NotNull UUID> expectedEligibleJobIds
) { }
