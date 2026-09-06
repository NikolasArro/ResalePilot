package ee.nikolas.resalepilot.workflow.yaga.shopimport.dto;

import jakarta.validation.constraints.Size;

public record YagaShopImportPrepareRequest(
        Integer maxItems,
        @Size(min = 1, max = 120)
        String idempotencyKey
) {
}
