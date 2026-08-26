package ee.nikolas.resalepilot.exception;

public class ProductImageNotFoundException extends RuntimeException {

    public ProductImageNotFoundException(
            Long productId,
            Long imageId
    ) {
        super(
                "Image " + imageId +
                        " not found for product " + productId
        );
    }
}