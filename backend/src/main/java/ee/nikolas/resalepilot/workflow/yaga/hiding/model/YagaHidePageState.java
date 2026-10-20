package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

public enum YagaHidePageState {
    PUBLISHED, DELETED, HIDDEN, SOLD, UNAVAILABLE, LOGIN, UNKNOWN;

    public boolean inactive() {
        return this == DELETED || this == HIDDEN || this == SOLD || this == UNAVAILABLE;
    }
}
