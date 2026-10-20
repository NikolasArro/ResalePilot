package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

public record YagaStrandedJobRepairResult(
        Long accountId,
        int autoSkippedCount,
        int dryRunCompletedCount
) { }
