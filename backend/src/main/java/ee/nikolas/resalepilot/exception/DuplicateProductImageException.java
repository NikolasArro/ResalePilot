package ee.nikolas.resalepilot.exception;

public class DuplicateProductImageException extends RuntimeException {

    public DuplicateProductImageException(
            Long productId,
            String driveFileId
    ) {
        super(
                "Drive file " + driveFileId +
                        " is already attached to product " + productId
        );
    }
}