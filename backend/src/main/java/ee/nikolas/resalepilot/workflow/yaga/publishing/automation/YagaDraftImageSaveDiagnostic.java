package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Request;
import java.net.URI;
import java.util.*;

/** Independent, bounded telemetry. No bodies, headers, image content or arbitrary URLs retained. */
final class YagaDraftImageSaveDiagnostic {
    static final int MAX_EVENTS = 64;
    private final long origin = System.nanoTime();
    private final Map<Request, Integer> ids = new IdentityHashMap<>();
    private final List<String> events = new ArrayList<>();
    private int dropped;
    private boolean responseObserved;
    private String lastEvent = "NONE";
    private long lastAt = origin;
    private String timeoutSnapshot;
    private Long listenersRegisteredMs;

    void listenersRegistered() { listenersRegisteredMs = elapsed(System.nanoTime() - origin); }

    void event(String kind, Request request, Integer status, boolean tracked) {
        try {
            if (!Set.of("REQUEST", "PENDING_ADDED", "RESPONSE", "FINISHED", "FAILED", "PARSED_SAVED", "PARSED_REJECTED", "UNREADABLE").contains(kind)) return;
            URI uri = URI.create(request.url());
            if (!"https".equals(uri.getScheme()) || !("www.yaga.ee".equals(uri.getHost()) || "yaga.ee".equals(uri.getHost()))) return;
            String path = uri.getPath();
            if (!path.startsWith("/api/product/") || !path.contains("/images")) return;
            String method = Set.of("POST", "PATCH", "PUT", "GET", "DELETE", "OPTIONS").contains(request.method()) ? request.method() : "OTHER";
            String route = path.matches("/api/product/[1-9][0-9]*/images/?") ? "IMAGE_SAVE" : "OTHER_IMAGE_API";
            Integer id = ids.get(request);
            if (id == null && ids.size() < MAX_EVENTS) { id = ids.size() + 1; ids.put(request, id); }
            if ("RESPONSE".equals(kind)) responseObserved = true;
            lastAt = System.nanoTime();
            lastEvent = kind + ":r" + (id == null ? 0 : id) + ":" + method + ":" + route + ":tracked=" + tracked +
                    (status == null ? "" : ":status=" + status);
            if (events.size() < MAX_EVENTS) events.add(elapsed(lastAt - origin) + "ms:" + lastEvent);
            else dropped = Math.min(999_999, dropped + 1);
        } catch (RuntimeException ignored) { /* Telemetry must not alter readiness. */ }
    }

    void timedOut(boolean saved, int pending, String phase) { timeoutSnapshot = state(saved, pending, phase); }

    private String state(boolean saved, int pending, String phase) {
        return "phase=" + phase + ";listenersRegisteredMs=" + listenersRegisteredMs + ";elapsedMs=" + elapsed(System.nanoTime() - origin) +
                ";imagesSaved=" + saved + ";pendingImages=" + pending + ";imageResponseObserved=" + responseObserved +
                ";lastEvent=" + lastEvent + ";sinceLastEventMs=" + elapsed(System.nanoTime() - lastAt);
    }

    String summary(boolean saved, int pending) {
        String result = "v=1;" + (timeoutSnapshot == null ? state(saved, pending, "FAILURE") : timeoutSnapshot) +
                ";events=" + events + ";eventsDropped=" + dropped;
        return result.substring(0, Math.min(12_000, result.length()));
    }

    private static long elapsed(long nanos) { return Math.min(999_999, Math.max(0, nanos / 1_000_000)); }
}
