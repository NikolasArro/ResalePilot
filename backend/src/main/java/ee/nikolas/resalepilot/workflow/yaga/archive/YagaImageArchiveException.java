package ee.nikolas.resalepilot.workflow.yaga.archive;

import java.util.OptionalInt;

public class YagaImageArchiveException
        extends RuntimeException {

    private final YagaImageArchiveFailureCode code;
    private final String safeMessage;
    private final Integer failedImageCount;

    public YagaImageArchiveException(
            YagaImageArchiveFailureCode code,
            String safeMessage,
            Integer failedImageCount,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.code = code;
        this.safeMessage = safeMessage;
        this.failedImageCount = failedImageCount;
    }

    public YagaImageArchiveFailureCode getCode() {
        return code;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public OptionalInt getFailedImageCount() {
        return failedImageCount == null
                ? OptionalInt.empty()
                : OptionalInt.of(failedImageCount);
    }
}
