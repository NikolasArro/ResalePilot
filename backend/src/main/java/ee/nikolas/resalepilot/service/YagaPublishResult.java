package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaPublicationStatus;

import java.time.Instant;

public record YagaPublishResult(
        boolean clickPerformed,
        YagaPublicationStatus status,
        String productUrl,
        String shopSlug,
        String productSlug,
        Instant publishedAt
) {
}
