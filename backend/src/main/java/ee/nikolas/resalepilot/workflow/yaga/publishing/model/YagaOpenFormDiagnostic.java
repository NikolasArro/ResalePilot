package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

/** Content-free evidence for one create-form navigation and readiness check. */
public record YagaOpenFormDiagnostic(
        Snapshot initial,
        boolean readinessWaitAttempted,
        boolean readinessWaitCompleted,
        boolean readinessWaitTimedOut,
        String waitExceptionClass,
        Snapshot firstTimeout,
        boolean readyAtFirstTimeout,
        boolean additionalWaitAttempted,
        boolean additionalWaitCompleted,
        boolean additionalWaitTimedOut,
        String additionalWaitExceptionClass,
        Snapshot settled,
        boolean finalReady,
        Reason reason
) {
    public YagaOpenFormDiagnostic(
            Snapshot initial, boolean readinessWaitAttempted,
            boolean readinessWaitCompleted, boolean readinessWaitTimedOut,
            String waitExceptionClass, Snapshot settled, Reason reason
    ) {
        this(initial, readinessWaitAttempted, readinessWaitCompleted,
                readinessWaitTimedOut, waitExceptionClass, null, false,
                false, false, false, null, settled, reason == Reason.READY,
                reason);
    }

    public enum Reason {
        READY,
        LOGIN_REDIRECT,
        VISIBLE_LOGIN_CONTROL,
        HIDDEN_LOGIN_MATCH,
        WRONG_PATH,
        CREATE_FORM_NOT_READY,
        READINESS_TIMEOUT,
        READINESS_EXCEPTION
    }

    public record Snapshot(
            String pathname,
            int loginMatchCount,
            int visibleLoginCount,
            int descriptionFoundCount,
            int visibleDescriptionCount,
            int categoryFoundCount,
            int visibleCategoryCount,
            int visibleLoadingCount
    ) {
        public String safeSummary() {
            return "pathname=" + pathname + ",loginMatches=" + loginMatchCount +
                    ",visibleLogin=" + visibleLoginCount +
                    ",descriptionFound=" + descriptionFoundCount +
                    ",visibleDescription=" + visibleDescriptionCount +
                    ",categoryFound=" + categoryFoundCount +
                    ",visibleCategory=" + visibleCategoryCount +
                    ",visibleLoading=" + visibleLoadingCount;
        }
    }

    public String safeSummary() {
        return "initial={" + initial.safeSummary() + "};readinessWaitAttempted=" + readinessWaitAttempted +
                ";readinessWaitCompleted=" + readinessWaitCompleted +
                ";readinessWaitTimedOut=" + readinessWaitTimedOut +
                ";waitExceptionClass=" + waitExceptionClass +
                ";firstTimeout=" + (firstTimeout == null ? null : "{" + firstTimeout.safeSummary() + "}") +
                ";readyAtFirstTimeout=" + readyAtFirstTimeout +
                ";additionalWaitAttempted=" + additionalWaitAttempted +
                ";additionalWaitCompleted=" + additionalWaitCompleted +
                ";additionalWaitTimedOut=" + additionalWaitTimedOut +
                ";additionalWaitExceptionClass=" + additionalWaitExceptionClass +
                ";settled={" + settled.safeSummary() + "}" +
                ";finalReady=" + finalReady + ";reason=" + reason;
    }
}
