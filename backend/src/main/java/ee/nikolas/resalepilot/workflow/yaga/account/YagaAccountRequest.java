package ee.nikolas.resalepilot.workflow.yaga.account;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YagaAccountRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 150) String shopSlug,
        @Size(max = 500) String authStatePath,
        @Size(max = 200) String driveFolderId,
        Boolean enabled,
        Boolean autoRefreshEnabled,
        @Min(1) Integer batchSize
) {
}
