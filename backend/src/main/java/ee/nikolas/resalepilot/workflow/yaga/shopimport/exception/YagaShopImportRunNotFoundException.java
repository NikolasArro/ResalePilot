package ee.nikolas.resalepilot.workflow.yaga.shopimport.exception;

import java.util.UUID;

public class YagaShopImportRunNotFoundException extends RuntimeException {

    public YagaShopImportRunNotFoundException(UUID runId) {
        super("Yaga shop import run not found: " + runId);
    }
}
