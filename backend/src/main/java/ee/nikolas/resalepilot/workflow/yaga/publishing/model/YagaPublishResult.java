package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;

import java.time.Instant;

public record YagaPublishResult(
        boolean clickPerformed,
        YagaPublicationStatus status,
        String productUrl,
        String shopSlug,
        String productSlug,
        Instant publishedAt,
        YagaPublicationEvidence evidence
) {
    public YagaPublishResult(boolean clickPerformed, YagaPublicationStatus status,
                             String productUrl, String shopSlug, String productSlug,
                             Instant publishedAt) {
        this(clickPerformed, status, productUrl, shopSlug, productSlug, publishedAt, null);
    }
}
