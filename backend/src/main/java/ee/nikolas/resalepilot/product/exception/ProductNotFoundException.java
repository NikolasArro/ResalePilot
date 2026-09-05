package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product not found with id: " + id);
    }
}