package ee.nikolas.resalepilot.workflow.yaga.shopimport.entity;

public enum YagaShopImportItemStatus {
    SELECTED,
    IMPORTING,
    IMPORTED,
    ALREADY_EXISTS,
    SKIPPED_NOT_ACTIVE,
    SKIPPED_INVALID_DATA,
    FAILED
}
