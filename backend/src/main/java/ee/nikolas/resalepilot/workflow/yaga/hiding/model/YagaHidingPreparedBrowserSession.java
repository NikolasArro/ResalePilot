package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

public interface YagaHidingPreparedBrowserSession {

    YagaHidingDraftData draft();

    default YagaHideDiagnostic hideDiagnostic() { return null; }
}
