package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaListingDraftData;

import java.util.UUID;

public interface YagaPreparedBrowserSession {

    UUID sessionId();

    YagaListingDraftData draft();

    YagaFormFillResult preparedForm();
}
