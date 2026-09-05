package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedBrowserSession;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedImageFile;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;

import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;

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
