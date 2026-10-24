package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Captures initialization before navigation; retains only allowlisted identifiers and diagnostics. */
final class YagaDraftPreparationObserver implements AutoCloseable {
    record Identity(long id, String slug) { }
    private static final Logger log = LoggerFactory.getLogger(YagaDraftPreparationObserver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Page page;
    private final Long oldListingId;
    private final String shop;
    private final Set<Request> pendingImages = new HashSet<>();
    private final Set<Long> imageDraftIds = new HashSet<>();
    private Request creation;
    private Identity identity;
    private String failure;
    private Integer httpStatus;
    private String caseId;
    private boolean imagesSaved;
    private final YagaDraftImageSaveDiagnostic imageDiagnostic = new YagaDraftImageSaveDiagnostic();
    private final Consumer<Request> requested = this::requested;
    private final Consumer<Request> finished = this::finished;
    private final Consumer<Request> failed = this::failed;
    private final Consumer<Response> response = r -> {
        if (r.request() == creation) httpStatus = r.status();
        imageDiagnostic.event("RESPONSE", r.request(), r.status(), pendingImages.contains(r.request()));
    };

    YagaDraftPreparationObserver(Page page, Long oldListingId, String shop) {
        this.page = page;
        this.oldListingId = oldListingId;
        this.shop = shop;
        page.addInitScript("""
                (() => {
                  window.__resalePilotDraftError = false;
                  window.__resalePilotDraftCaseId = null;
                  new MutationObserver(() => {
                    const alerts = [...document.querySelectorAll('.notistack-MuiContent-error, .notistack-case-id, [role="alert"]')];
                    const code = alerts.map(e => e.textContent.match(/\\bEE-A-[A-Za-z0-9-]{1,100}\\b/)?.[0]).find(Boolean);
                    if (code) window.__resalePilotDraftCaseId = code;
                    if (alerts
                        .some(e => e.getClientRects().length &&
                          (e.matches('.notistack-MuiContent-error') || /EE-A-[A-Za-z0-9-]+/.test(e.textContent))))
                      window.__resalePilotDraftError = true;
                  }).observe(document, {childList:true, subtree:true, attributes:true});
                })();
                """);
        try {
            page.onRequest(requested);
            page.onResponse(response);
            page.onRequestFinished(finished);
            page.onRequestFailed(failed);
            imageDiagnostic.listenersRegistered();
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    Identity requireInitialized(double timeoutMs) {
        await(() -> identity != null || failure != null, timeoutMs, "INITIALIZATION");
        requireHealthy();
        if (identity == null) reject("DRAFT_INITIALIZATION_NOT_CONFIRMED");
        return identity;
    }

    Identity requireReady(double timeoutMs) {
        requireInitialized(timeoutMs);
        await(() -> (imagesSaved && pendingImages.isEmpty()) || failure != null, timeoutMs, "IMAGE_SAVE");
        requireHealthy();
        if (!imagesSaved || !pendingImages.isEmpty()) reject("DRAFT_IMAGES_NOT_CONFIRMED");
        log.info("Yaga draft preparation ready: oldListingId={} draftId={} draftSlug={} httpStatus={}",
                oldListingId, identity.id(), identity.slug(), httpStatus);
        return identity;
    }

    private void await(java.util.function.BooleanSupplier condition, double timeoutMs, String phase) {
        try {
            page.waitForCondition(() -> {
                if (hasError(page) && failure == null) failure = "PREPARATION_UI_ERROR";
                return failure != null || condition.getAsBoolean();
            }, new Page.WaitForConditionOptions().setTimeout(timeoutMs));
        } catch (com.microsoft.playwright.TimeoutError ignored) {
            imageDiagnostic.timedOut(imagesSaved, pendingImages.size(), phase);
            failure = "DRAFT_PREPARATION_TIMEOUT";
        }
    }

    private void requireHealthy() {
        if (identity != null && imageDraftIds.stream().anyMatch(id -> id != identity.id()))
            failure = "DRAFT_IMAGE_ID_MISMATCH";
        if (hasError(page) && failure == null) failure = "PREPARATION_UI_ERROR";
        if (failure != null) reject(failure);
    }

    static void requireNoError(Page page) {
        if (hasError(page)) throw new YagaPublishingFormException("Yaga preparation contains an error notification");
    }

    private static boolean hasError(Page page) {
        return Boolean.TRUE.equals(page.evaluate("""
                () => window.__resalePilotDraftError === true || [...document.querySelectorAll('.notistack-MuiContent-error, .notistack-case-id, [role="alert"]')]
                  .some(e => e.getClientRects().length &&
                    (e.matches('.notistack-MuiContent-error') || /EE-A-[A-Za-z0-9-]+/.test(e.textContent)))
                """));
    }

    private void requested(Request request) {
        imageDiagnostic.event("REQUEST", request, null, pendingImages.contains(request));
        String path = path(request);
        if ("POST".equals(request.method()) && path.matches("/api/product/?")) {
            if (creation != null) failure = "MULTIPLE_DRAFT_INITIALIZATIONS";
            else creation = request;
            log.info("Yaga draft initialization started: oldListingId={} method=POST path={}", oldListingId, path);
        } else if ("POST".equals(request.method()) && path.matches("/api/product/[1-9][0-9]*/images/?")) {
            pendingImages.add(request);
            imageDiagnostic.event("PENDING_ADDED", request, null, pendingImages.contains(request));
            long id = Long.parseLong(path.split("/")[3]);
            imageDraftIds.add(id);
            if (identity != null && identity.id() != id) failure = "DRAFT_IMAGE_ID_MISMATCH";
            log.info("Yaga draft image request: oldListingId={} draftId={} requestDraftId={} method=POST path={}",
                    oldListingId, identity == null ? null : identity.id(), id, path);
        }
    }

    private void finished(Request request) {
        imageDiagnostic.event("FINISHED", request, null, pendingImages.contains(request));
        if (request != creation && !pendingImages.contains(request)) return;
        try {
            Response response = request.response();
            if (request == creation) httpStatus = response.status();
            String body = response.text();
            if (body.length() > 1_000_000) throw new IllegalArgumentException();
            var root = JSON.readTree(body);
            var data = root.path("data");
            String responseCase = YagaPublicationResponseParser.safeCaseId(data.path("caseId").asText(""));
            String code = YagaPublicationResponseParser.safeCode(data.path("code").asText(data.path("errorCode").asText("")));
            if (responseCase != null) caseId = responseCase;
            if (request == creation) {
                httpStatus = response.status();
                long id = data.path("id").asLong(0);
                String slug = data.path("slug").asText("");
                String actualShop = data.path("shop").path("slug").asText("");
                if (httpStatus >= 200 && httpStatus < 300 && "success".equals(root.path("status").asText()) &&
                        id > 0 && slug.matches("[a-z0-9][a-z0-9_-]{1,149}") &&
                        (actualShop.isEmpty() || actualShop.equals(shop))) {
                    identity = new Identity(id, slug);
                } else failure = "DRAFT_INITIALIZATION_REJECTED";
            } else {
                boolean imageSaved = YagaPublicationResponseParser.imageOrderSaved(response.status(), body);
                if (!imageSaved) failure = "DRAFT_IMAGE_SAVE_FAILED";
                else imagesSaved = true;
                pendingImages.remove(request);
                imageDiagnostic.event(imageSaved ? "PARSED_SAVED" : "PARSED_REJECTED", request, response.status(), pendingImages.contains(request));
            }
            log.info("Yaga draft preparation response: oldListingId={} method={} path={} httpStatus={} " +
                            "draftId={} draftSlug={} caseId={} errorCode={} failure={} responseShape={}",
                    oldListingId, request.method(), path(request), response.status(),
                    identity == null ? null : identity.id(), identity == null ? null : identity.slug(),
                    responseCase, code, failure, YagaPublicationResponseParser.shape(body));
        } catch (RuntimeException ignored) {
            imageDiagnostic.event("UNREADABLE", request, null, pendingImages.contains(request));
            failure = "DRAFT_RESPONSE_UNREADABLE";
            log.warn("Yaga draft response unreadable: oldListingId={} method={} path={} httpStatus={}",
                    oldListingId, request.method(), path(request), httpStatus);
        }
    }

    private void failed(Request request) {
        imageDiagnostic.event("FAILED", request, null, pendingImages.contains(request));
        if (request == creation || pendingImages.contains(request)) failure = "DRAFT_REQUEST_FAILED";
    }

    static String path(Request request) {
        String safe = YagaPublicationSubmitObserver.safeUrl(request.url());
        if (!safe.startsWith("https://")) return "";
        int pathStart = safe.indexOf('/', "https://".length());
        return pathStart < 0 ? "" : safe.substring(pathStart);
    }

    private void reject(String reason) {
        if (caseId == null) {
            Object code = page.evaluate("() => window.__resalePilotDraftCaseId || null");
            if (code instanceof String value) caseId = YagaPublicationResponseParser.safeCaseId(value);
        }
        log.warn("Yaga draft preparation blocked: oldListingId={} draftId={} httpStatus={} caseId={} reason={}",
                oldListingId, identity == null ? null : identity.id(), httpStatus, caseId, reason);
        String diagnostic = imageSaveDiagnostic();
        log.warn("Yaga draft image-save diagnostic: oldListingId={} draftId={} {}",
                oldListingId, identity == null ? null : identity.id(), diagnostic);
        throw new YagaPublishingFormException("Yaga draft preparation failed: " + reason +
                "; httpStatus=" + httpStatus + "; caseId=" + caseId,
                YagaPublishingFormDiagnostics.failureMetadata(null, null, null, null)
                        .withDraftImageSaveDiagnostic(diagnostic));
    }

    String imageSaveDiagnostic() { return imageDiagnostic.summary(imagesSaved, pendingImages.size()); }

    @Override public void close() {
        cleanup(() -> page.offRequest(requested));
        cleanup(() -> page.offResponse(response));
        cleanup(() -> page.offRequestFinished(finished));
        cleanup(() -> page.offRequestFailed(failed));
    }

    private static void cleanup(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* Attempt every removal even if the page has closed. */ }
    }
}
