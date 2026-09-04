package ee.nikolas.resalepilot.service;

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
