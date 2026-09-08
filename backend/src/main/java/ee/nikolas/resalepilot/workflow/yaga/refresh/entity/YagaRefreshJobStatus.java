package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

public enum YagaRefreshJobStatus {
    SELECTED,
    PUBLISHING,
    NEW_LISTING_CONFIRMED,
    HIDING_OLD,
    COMPLETED,
    RESULT_UNKNOWN,
    DRY_RUN_COMPLETED,
    FAILED
}
