package ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity;

public enum YagaBatchArchiveRunStatus {
    PREPARING,
    AWAITING_CONFIRMATION,
    ARCHIVING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}
