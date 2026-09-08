package ee.nikolas.resalepilot.workflow.yaga.refresh.repository;

import java.time.Instant;

public interface YagaRefreshCandidateRow {
    Long getListingId();

    Long getProductId();

    String getSku();

    String getTitle();

    String getExternalListingId();

    String getShopSlug();

    String getProductSlug();

    String getExternalUrl();

    Instant getExternalCreatedAt();

    Instant getListingCreatedAt();

    Instant getOrderingTimestamp();

    Integer getProductImageCount();

    Integer getListingImageCount();
}
