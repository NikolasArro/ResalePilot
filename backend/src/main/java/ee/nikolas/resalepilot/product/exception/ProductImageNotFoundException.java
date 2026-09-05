package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

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