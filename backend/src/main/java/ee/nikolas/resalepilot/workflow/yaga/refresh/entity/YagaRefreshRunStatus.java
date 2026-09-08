package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

public enum YagaRefreshRunStatus {
    CREATED,
    SELECTING,
    PREPARING,
    AWAITING_CONFIRMATION,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    DRY_RUN_COMPLETED,
    FAILED
}
