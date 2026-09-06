package ee.nikolas.resalepilot.workflow.yaga.shopimport.entity;

public enum YagaShopImportRunStatus {
    PREPARING,
    AWAITING_CONFIRMATION,
    IMPORTING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}
