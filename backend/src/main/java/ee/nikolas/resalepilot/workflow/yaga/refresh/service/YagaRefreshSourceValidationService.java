package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException;
import org.springframework.stereotype.Service;

@Service
public class YagaRefreshSourceValidationService {
    private final YagaPageDataClient pageDataClient;

    public YagaRefreshSourceValidationService(YagaPageDataClient pageDataClient) {
        this.pageDataClient = pageDataClient;
    }

    public void requireActive(Source source) {
        YagaImportedProductData data;
        try {
            data = pageDataClient.getProduct(source.url());
        } catch (RuntimeException exception) {
            // Do not expose response bodies or remote exception messages.
            throw new YagaRefreshInvalidStateException(
                    "SOURCE_VALIDATION_UNAVAILABLE: source listing status could not be verified");
        }
        if (data == null || data.externalId() == null ||
                !data.externalId().toString().equals(source.externalId()) ||
                !source.shopSlug().equals(data.shopSlug()) ||
                !source.productSlug().equals(data.productSlug())) {
            throw new YagaRefreshInvalidStateException(
                    "SOURCE_IDENTITY_UNVERIFIED: source listing identity could not be verified");
        }
        if (data.hiddenAt() != null || data.deletedAt() != null ||
                "sold".equals(data.status()) || "hidden".equals(data.status()) ||
                "deleted".equals(data.status()) || "not-visible".equals(data.status())) {
            throw new YagaRefreshSourceInactiveException();
        }
        // Use the same positive published-state check as hide reconciliation.
        // All other statuses (including sold, pending and unknown) fail closed.
        if (!"published".equals(data.status()) ||
                data.hiddenAt() != null || data.deletedAt() != null) {
            throw new YagaRefreshInvalidStateException(
                    "SOURCE_NOT_ACTIVE: source listing is not confirmed published and active");
        }
    }

    public record Source(String externalId, String shopSlug, String productSlug, String url) { }
}
