package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductStatus;

public class InvalidProductStatusTransitionException
        extends RuntimeException {

    public InvalidProductStatusTransitionException(
            ProductStatus currentStatus,
            ProductStatus targetStatus
    ) {
        super(
                "Cannot change product status from " +
                        currentStatus +
                        " to " +
                        targetStatus
        );
    }
}