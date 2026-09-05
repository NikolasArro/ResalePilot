package ee.nikolas.resalepilot.workflow.yaga.hiding.dto;

public enum YagaHidingStatus {
    PREPARING,
    AWAITING_CONFIRMATION,
    HIDING,
    HIDDEN,
    HIDDEN_DB_SYNC_FAILED,
    HIDE_RESULT_UNKNOWN,
    FAILED,
    CANCELLED,
    EXPIRED
}
