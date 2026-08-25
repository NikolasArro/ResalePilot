package ee.nikolas.resalepilot.exception;

import ee.nikolas.resalepilot.entity.ProductStatus;

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