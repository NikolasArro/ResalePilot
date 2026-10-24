package ee.nikolas.resalepilot.workflow.yaga.hiding.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Content-free telemetry only; never consulted for hide decisions. Times are callback receipt times. */
public final class YagaHideDiagnostic {
    private final long origin = System.nanoTime();
    private final List<String> events = new ArrayList<>();
    private int dropped;
    private int attempts;
    private long verificationStarted;
    private long duration;
    private String verificationOutcome = "NOT_STARTED";
    private boolean request, response, finished, failed, actionException;
    private static final class NetworkState { boolean response, finished; Integer status; }
    private final java.util.Map<Integer, NetworkState> requests = new java.util.HashMap<>();
    private Runnable cleanup;
    private boolean trackingLimitReached;
    private String lastFailureReason = "NONE";

    public synchronized void event(String value) {
        if (events.size() < 64) events.add(ms(System.nanoTime() - origin) + "ms:" + value);
        else dropped = Math.min(999_999, dropped + 1);
    }

    public synchronized void actionStarted() { event("ACTION_INITIATED"); }
    public synchronized void actionCompleted() { event("ACTION_COMPLETED"); }
    public synchronized void actionFailed(RuntimeException exception) {
        actionException = true;
        event("ACTION_EXCEPTION:" + error(exception));
    }
    public synchronized void network(String kind, int id, String method, Integer status, String reason) {
        if (id == 0) trackingLimitReached = true;
        if ("FAILED".equals(kind)) lastFailureReason = reason;
        switch (kind) {
            case "REQUEST" -> request = true;
            case "RESPONSE" -> response = true;
            case "FINISHED" -> finished = true;
            case "FAILED" -> failed = true;
            default -> { return; }
        }
        if ((id > 0 && requests.size() < 64) || requests.containsKey(id)) {
            NetworkState state = requests.computeIfAbsent(id, ignored -> new NetworkState());
            if ("RESPONSE".equals(kind)) { state.response = true; state.status = status; }
            if ("FINISHED".equals(kind)) state.finished = true;
        }
        event(kind + ":r" + id + ":" + method + (status == null ? "" : ":status=" + status) +
                (reason == null ? "" : ":reason=" + reason));
    }
    public synchronized void verificationStarted() {
        verificationStarted = System.nanoTime();
        verificationOutcome = "RUNNING";
        event("VERIFICATION_STARTED");
    }
    public synchronized void poll(String status, boolean identity, boolean pollAccepted, boolean accepted) {
        attempts = Math.min(999_999, attempts + 1);
        String safeStatus = Set.of("published", "hidden", "not-visible", "peidetud", "sold", "deleted", "draft", "pending")
                .contains(status == null ? "" : status) ? status : "UNKNOWN";
        event("POLL:" + attempts + ":status=" + safeStatus + ":identity=" + identity + ":pollAccepted=" + pollAccepted + ":accepted=" + accepted);
    }
    public synchronized void pollFailed(RuntimeException exception) {
        attempts = Math.min(999_999, attempts + 1);
        event("POLL:" + attempts + ":error=" + error(exception));
    }
    public synchronized void verificationEnded(String outcome) {
        duration = ms(System.nanoTime() - verificationStarted);
        verificationOutcome = outcome;
        event("VERIFICATION_" + outcome);
    }
    public synchronized void verificationFailed() {
        if ("RUNNING".equals(verificationOutcome)) verificationEnded("UNCONFIRMED");
        else event("VERIFICATION_UNCONFIRMED");
    }
    public synchronized String summary() {
        String action = actionException ? "BROWSER_ACTION_EXCEPTION" : failed ? "REQUEST_FAILED" : trackingLimitReached ? "TRACKING_LIMIT_REACHED" : !request ? "NO_RELEVANT_REQUEST" :
                !response || requests.values().stream().anyMatch(r -> !r.response) ? "REQUEST_WITHOUT_RESPONSE" :
                !finished || requests.values().stream().anyMatch(r -> !r.finished) ? "RESPONSE_WITHOUT_FINISH" :
                requests.values().stream().allMatch(r -> r.status != null && r.status >= 200 && r.status < 300) ?
                        "HTTP_SUCCESS_FINISHED" : "REQUEST_FINISHED";
        String text = "v=1;timeBasis=callbackReceipt;action=" + action + ";requestObserved=" + request + ";responseObserved=" + response + ";requestFinished=" + finished +
                ";requestFailed=" + failed + ";lastFailureReason=" + lastFailureReason + ";httpIsNotHideProof=true;attempts=" + attempts +
                ";verificationMs=" + duration + ";verificationOutcome=" + verificationOutcome +
                ";events=" + events + ";eventsDropped=" + dropped;
        return text.substring(0, Math.min(12_000, text.length()));
    }
    public synchronized void onClose(Runnable value) { cleanup = value; }
    public void closeObservation() {
        Runnable action;
        synchronized (this) { action = cleanup; cleanup = null; }
        if (action != null) try { action.run(); } catch (RuntimeException ignored) { /* diagnostic only */ }
    }
    public static String error(RuntimeException exception) {
        if (exception instanceof com.microsoft.playwright.TimeoutError) return "TIMEOUT";
        if (exception instanceof com.microsoft.playwright.PlaywrightException) return "BROWSER_ERROR";
        return "READ_OR_RUNTIME_ERROR";
    }
    private static long ms(long nanos) { return Math.min(999_999, Math.max(0, nanos / 1_000_000)); }
}
