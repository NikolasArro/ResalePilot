package ee.nikolas.resalepilot.dto;

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
