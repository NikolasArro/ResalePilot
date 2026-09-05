package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;

import java.util.UUID;

public interface YagaPreparedBrowserSession {

    UUID sessionId();

    YagaListingDraftData draft();

    YagaFormFillResult preparedForm();
}
