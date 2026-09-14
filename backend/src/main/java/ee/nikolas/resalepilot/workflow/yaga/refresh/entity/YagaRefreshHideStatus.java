package ee.nikolas.resalepilot.workflow.yaga.refresh.entity;

public enum YagaRefreshHideStatus {
    NOT_STARTED,
    AWAITING_CONFIRMATION,
    CONFIRMING,
    RESULT_UNKNOWN,
    TARGET_INVALID,
    HIDDEN,
    EXPIRED,
    CANCELLED
}
