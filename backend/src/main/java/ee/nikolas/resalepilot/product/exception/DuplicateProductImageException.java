package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

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