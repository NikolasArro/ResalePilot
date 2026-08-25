package ee.nikolas.resalepilot.entity;

public enum ProductStatus {

    DRAFT,
    READY,
    SCHEDULED,
    LISTED,
    SOLD,
    ARCHIVED;

    public boolean canTransitionTo(ProductStatus targetStatus) {
        if (targetStatus == null) {
            return false;
        }

        if (this == targetStatus) {
            return true;
        }

        return switch (this) {
            case DRAFT ->
                    targetStatus == READY ||
                            targetStatus == ARCHIVED;

            case READY ->
                    targetStatus == DRAFT ||
                            targetStatus == SCHEDULED ||
                            targetStatus == LISTED ||
                            targetStatus == ARCHIVED;

            case SCHEDULED ->
                    targetStatus == READY ||
                            targetStatus == LISTED ||
                            targetStatus == ARCHIVED;

            case LISTED ->
                    targetStatus == READY ||
                            targetStatus == SOLD ||
                            targetStatus == ARCHIVED;

            case SOLD ->
                    targetStatus == ARCHIVED;

            case ARCHIVED ->
                    targetStatus == DRAFT;
        };
    }
}