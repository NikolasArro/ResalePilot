package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaListingDraftData;

import java.util.List;

public interface YagaBrowserAutomation {

    YagaFormFillResult prepareForm(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    );

    YagaPreparedBrowserSession prepareSession(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    );

    YagaFormFillResult verifyPreparedForm(
            YagaPreparedBrowserSession session
    );

    YagaPublishControlInspection inspectPublishControl(
            YagaPreparedBrowserSession session
    );

    YagaPublishResult publishPreparedSession(
            YagaPreparedBrowserSession session
    );

    void closeSession(YagaPreparedBrowserSession session);
}
