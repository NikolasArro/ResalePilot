package ee.nikolas.resalepilot.exception;

public class InvalidProductImageOrderException
        extends RuntimeException {

    public InvalidProductImageOrderException(Long productId) {
        super(
                "Image order must contain every image " +
                        "of product " + productId + " exactly once"
        );
    }
}