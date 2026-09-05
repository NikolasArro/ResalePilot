package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

public class InvalidProductImageOrderException
        extends RuntimeException {

    public InvalidProductImageOrderException(Long productId) {
        super(
                "Image order must contain every image " +
                        "of product " + productId + " exactly once"
        );
    }
}