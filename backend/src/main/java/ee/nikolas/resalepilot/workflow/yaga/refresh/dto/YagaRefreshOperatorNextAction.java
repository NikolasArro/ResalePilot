package ee.nikolas.resalepilot.workflow.yaga.refresh.dto;

public enum YagaRefreshOperatorNextAction {
    PREPARE_PUBLICATION,
    CHECK_PUBLICATION_READINESS,
    CONFIRM_PUBLICATION,
    RECONCILE_PUBLICATION,
    PREPARE_HIDE,
    CHECK_HIDE_READINESS,
    CONFIRM_HIDE,
    RECONCILE_HIDE,
    RUN_COMPLETED,
    RUN_CANCELLED,
    BLOCKED,
    NO_CANDIDATES
}
