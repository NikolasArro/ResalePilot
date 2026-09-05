package ee.nikolas.resalepilot.workflow.yaga.publishing.dto;

public enum YagaPublicationStatus {
    PREPARING,
    AWAITING_CONFIRMATION,
    PUBLISHING,
    PUBLISHED,
    PUBLISHED_DB_SYNC_FAILED,
    PUBLISH_RESULT_UNKNOWN,
    FAILED,
    CANCELLED,
    EXPIRED
}
