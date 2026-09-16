package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideResult;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;

public interface YagaHidingBrowserAutomation {

    YagaHidingPreparedBrowserSession prepareSession(
            YagaHidingDraftData draft
    );

    default YagaHidingPreparedBrowserSession prepareSession(
            YagaHidingDraftData draft,
            YagaAccount account
    ) {
        return prepareSession(draft);
    }

    YagaHideControlInspection inspectHideControl(
            YagaHidingPreparedBrowserSession session
    );

    default void ensureOnHideTarget(
            YagaHidingPreparedBrowserSession session
    ) {
    }

    YagaHideResult hidePreparedSession(
            YagaHidingPreparedBrowserSession session
    );

    void closeSession(YagaHidingPreparedBrowserSession session);
}
