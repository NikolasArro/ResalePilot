package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideResult;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;

public interface YagaHidingBrowserAutomation {

    YagaHidingPreparedBrowserSession prepareSession(
            YagaHidingDraftData draft
    );

    YagaHideControlInspection inspectHideControl(
            YagaHidingPreparedBrowserSession session
    );

    YagaHideResult hidePreparedSession(
            YagaHidingPreparedBrowserSession session
    );

    void closeSession(YagaHidingPreparedBrowserSession session);
}
