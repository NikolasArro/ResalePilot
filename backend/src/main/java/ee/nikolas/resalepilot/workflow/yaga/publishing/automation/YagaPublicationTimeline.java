package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Diagnostic only. Never reads bodies, headers, form values or error messages. */
final class YagaPublicationTimeline implements AutoCloseable {
    static final int MAX_EVENTS = 64;
    private final Page page;
    private long origin = System.nanoTime();
    private record Event(long at, String kind) {}
    private final List<Event> events = new ArrayList<>();
    private final Map<Request, Integer> requestIds = new IdentityHashMap<>();
    private int dropped;
    private final Consumer<Request> request = r -> network("request", r, null);
    private final Consumer<Response> response = r -> safe(() -> network("response", r.request(), "status=" + r.status()));
    private final Consumer<Request> finished = r -> network("finished", r, null);
    private final Consumer<Request> failed = r -> safe(() -> network("failed", r, "reason=" + failure(r.failure())));
    private final Consumer<String> error = ignored -> add("pageerror");
    private final Consumer<ConsoleMessage> console = m -> safe(() -> { if ("error".equals(m.type())) add("console-error"); });

    YagaPublicationTimeline(Page page) { this.page = page; }

    void start() {
        // Each registration is inside the caller's try/finally, including partial startup.
        safe(() -> page.onRequest(request));
        safe(() -> page.onResponse(response));
        safe(() -> page.onRequestFinished(finished));
        safe(() -> page.onRequestFailed(failed));
        safe(() -> page.onPageError(error));
        safe(() -> page.onConsoleMessage(console));
        safe(() -> page.evaluate(INSTALL));
    }

    void clickStarted() { origin = System.nanoTime(); add("click-invocation"); safe(() -> page.evaluate("() => window.__rpPublicationTimeline?.begin()")); }

    private void network(String kind, Request r, String extra) {
        safe(() -> {
            URI uri = URI.create(r.url());
            if (!"https".equals(uri.getScheme()) ||
                    !("www.yaga.ee".equals(uri.getHost()) || "yaga.ee".equals(uri.getHost()))) return;
            String method = Set.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS", "HEAD").contains(r.method()) ? r.method() : "OTHER";
            // No arbitrary path segments (which can contain tokens or personal information).
            String route = uri.getPath().matches("/api/product(?:/.*)?") ? "product-api" : "other-yaga";
            Integer id = requestIds.get(r);
            if (id == null && requestIds.size() < MAX_EVENTS) { id = requestIds.size() + 1; requestIds.put(r, id); }
            add(kind + ":r" + (id == null ? 0 : id) + ":" + method + ":" + route + (extra == null ? "" : ":" + extra));
        });
    }

    static String failure(String raw) {
        return Set.of("net::ERR_FAILED", "net::ERR_ABORTED", "net::ERR_TIMED_OUT", "net::ERR_CONNECTION_RESET",
                "net::ERR_CONNECTION_REFUSED", "net::ERR_NAME_NOT_RESOLVED", "net::ERR_INTERNET_DISCONNECTED")
                .contains(raw == null ? "" : raw) ? raw : "REDACTED_OR_UNAVAILABLE";
    }

    private void add(String event) {
        if (events.size() < MAX_EVENTS) events.add(new Event(System.nanoTime(), event));
        else dropped = Math.min(999_999, dropped + 1);
    }

    String summary() {
        List<String> dom = new ArrayList<>();
        safe(() -> {
            Object snapshot = page.evaluate("() => window.__rpPublicationTimeline?.snapshot()");
            if (!(snapshot instanceof Map<?, ?> map)) return;
            if (map.get("events") instanceof List<?> list) for (Object entry : list.stream().limit(MAX_EVENTS).toList()) {
                if (!(entry instanceof Map<?, ?> e)) continue;
                Object kind = e.get("kind"), time = e.get("ms");
                if (kind instanceof String k && Set.of("click-capture", "click-bubble", "submit", "invalid", "inline-validation").contains(k)
                        && time instanceof Number n) dom.add( Math.max(-999_999, Math.min(999_999, n.longValue())) + "ms:" + k);
            }
            if (map.get("dropped") instanceof Number n) dropped = Math.min(999_999, dropped + Math.max(0, Math.min(999_999, n.intValue())));
        });
        String result = "timelineV=1;timeline=" + events.stream().map(e ->
                Math.max(-999_999, Math.min(999_999, (e.at() - origin) / 1_000_000)) + "ms:" + e.kind()).toList() +
                ";domTimeline=" + dom + ";timelineDropped=" + dropped;
        return result.substring(0, Math.min(12_000, result.length()));
    }

    private static void safe(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { /* Diagnostics cannot block publication. */ } }

    @Override public void close() {
        safe(() -> page.offRequest(request));
        safe(() -> page.offResponse(response));
        safe(() -> page.offRequestFinished(finished));
        safe(() -> page.offRequestFailed(failed));
        safe(() -> page.offPageError(error));
        safe(() -> page.offConsoleMessage(console));
        safe(() -> page.evaluate("() => window.__rpPublicationTimeline?.close()"));
    }

    static final String INSTALL = """
            () => {
              window.__rpPublicationTimeline?.close();
              const events = []; let dropped = 0; let origin = performance.now();
              const add = kind => { if (events.length < 64) events.push({kind, ms: Math.round(performance.now() - origin)}); else dropped = Math.min(999999, dropped + 1); };
              const isButton = e => e.target instanceof Element && e.target.closest('button')?.textContent.trim() === 'Valmis';
              const capture = e => { if (isButton(e)) add('click-capture'); };
              const bubble = e => { if (isButton(e)) add('click-bubble'); };
              const submit = () => add('submit');
              const invalid = () => add('invalid');
              // Retain only appearance of semantic errors, never their text or field values.
              const selector = '[aria-invalid="true"], [role="alert"], .MuiFormHelperText-root.Mui-error';
              const seen = new WeakSet();
              const scan = record => document.querySelectorAll(selector).forEach(e => {
                if (e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' && !seen.has(e)) {
                  seen.add(e); if (record) add('inline-validation');
                }
              });
              scan(false);
              const observer = new MutationObserver(() => scan(true));
              observer.observe(document, {subtree:true, childList:true, attributes:true, attributeFilter:['class','style','hidden','aria-invalid','role']});
              document.addEventListener('click', capture, true);
              document.addEventListener('click', bubble, false);
              document.addEventListener('submit', submit, true);
              document.addEventListener('invalid', invalid, true);
              window.__rpPublicationTimeline = {
                begin: () => { origin = performance.now(); },
                snapshot: () => ({events, dropped}),
                close: () => { observer.disconnect(); document.removeEventListener('click', capture, true);
                  document.removeEventListener('click', bubble, false); document.removeEventListener('submit', submit, true);
                  document.removeEventListener('invalid', invalid, true); delete window.__rpPublicationTimeline; }
              };
            }
            """;
}
