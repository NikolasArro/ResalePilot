package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

import java.nio.file.Path;
import java.util.List;

public record YagaHideTargetDiagnostics(
        Long requestedOldListingId,
        String expectedShopSlug,
        String expectedProductSlug,
        String expectedExternalListingId,
        String requestedManagementUrl,
        String currentUrl,
        String pageTitle,
        String resolvedAuthStatePath,
        boolean authStateFileExists,
        boolean authStateFileReadable,
        long authStateFileSize,
        boolean expectedProductSlugInDom,
        boolean expectedExternalListingIdInDom,
        boolean canonicalProductUrlInDom,
        boolean managementUrlContainsExpectedTarget,
        boolean editControlVisible,
        boolean hideControlVisible,
        boolean ownerControlsVisible,
        Path screenshotPath,
        List<YagaManagementControlDiagnostic> managementControls
) {
}
