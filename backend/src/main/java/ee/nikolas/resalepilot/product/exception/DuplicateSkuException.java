package ee.nikolas.resalepilot.product.exception;

import ee.nikolas.resalepilot.product.entity.Product;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("Product already exists with SKU: " + sku);
    }
}