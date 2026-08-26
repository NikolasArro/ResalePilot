package ee.nikolas.resalepilot.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderProductImagesRequest(

        @NotEmpty
        List<@NotNull Long> imageIds

) {
}