package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import com.microsoft.playwright.*;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideDiagnostic;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Observe only writes to the source-product API; never inspect payloads or change the click. */
final class YagaHideActionObserver implements AutoCloseable {
    private final Page page;
    private final String expectedId;
    private final YagaHideDiagnostic diagnostic;
    private final Map<Request, Integer> ids = new IdentityHashMap<>();
    private boolean closed;
    private final Consumer<Request> request = r -> observe("REQUEST", r, null);
    private final Consumer<Response> response = r -> safe(() -> observe("RESPONSE", r.request(), r.status()));
    private final Consumer<Request> finished = r -> observe("FINISHED", r, null);
    private final Consumer<Request> failed = r -> observe("FAILED", r, null);

    YagaHideActionObserver(Page page, String expectedId, YagaHideDiagnostic diagnostic) {
        this.page = page; this.expectedId = expectedId; this.diagnostic = diagnostic;
    }
    void start(Locator button) {
        safe(() -> page.onRequest(request)); safe(() -> page.onResponse(response));
        safe(() -> page.onRequestFinished(finished)); safe(() -> page.onRequestFailed(failed));
        safe(() -> button.evaluate("""
                button => {
                  window.__rpHideClick = {seen:false, origin:performance.now(), ms:null};
                  window.__rpHideListener = event => {
                    if (event.target instanceof Node && button.contains(event.target)) { window.__rpHideClick.seen = true; window.__rpHideClick.ms = performance.now() - window.__rpHideClick.origin; }
                  };
                  document.addEventListener('click', window.__rpHideListener, true);
                }
                """));
        diagnostic.onClose(this::close);
        diagnostic.event("LISTENERS_INSTALLED");
    }
    private void observe(String kind, Request r, Integer status) {
        safe(() -> {
            URI uri = URI.create(r.url());
            if (!"https".equals(uri.getScheme()) || !("www.yaga.ee".equals(uri.getHost()) || "yaga.ee".equals(uri.getHost()))) return;
            if (!Set.of("POST", "PATCH", "PUT", "DELETE").contains(r.method())) return;
            String path = uri.getPath();
            if (!("/api/product/" + expectedId).equals(path) && !path.startsWith("/api/product/" + expectedId + "/")) return;
            Integer id = ids.get(r);
            if (id == null && ids.size() < 64) { id = ids.size() + 1; ids.put(r, id); }
            String reason = null;
            if ("FAILED".equals(kind)) {
                String raw = r.failure();
                reason = Set.of("net::ERR_FAILED", "net::ERR_ABORTED", "net::ERR_TIMED_OUT", "net::ERR_CONNECTION_RESET",
                        "net::ERR_CONNECTION_REFUSED", "net::ERR_NAME_NOT_RESOLVED", "net::ERR_INTERNET_DISCONNECTED")
                        .contains(raw == null ? "" : raw) ? raw : "REDACTED_OR_UNAVAILABLE";
            }
            diagnostic.network(kind, id == null ? 0 : id, r.method(), status, reason);
        });
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        // Also pumps already queued Playwright events; no additional wait or hide attempt.
        safe(() -> {
            Object snapshot = page.evaluate("() => window.__rpHideClick");
            if (snapshot instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("seen"))) {
                long elapsed = map.get("ms") instanceof Number n ? Math.min(999_999, Math.max(0, n.longValue())) : 0;
                diagnostic.event("CLICK_DISPATCHED:domMs=" + elapsed);
            } else diagnostic.event("CLICK_DISPATCH_UNVERIFIED");
        });
        safe(() -> page.offRequest(request)); safe(() -> page.offResponse(response));
        safe(() -> page.offRequestFinished(finished)); safe(() -> page.offRequestFailed(failed));
        safe(() -> page.evaluate("() => { document.removeEventListener('click', window.__rpHideListener, true); delete window.__rpHideListener; delete window.__rpHideClick; }"));
    }
    private static void safe(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { /* diagnostic only */ } }
}
