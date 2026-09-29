package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublishedUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence.Outcome.*;

/** Observes a single click. Listeners are scoped to the prepared page and removed afterwards. */
final class YagaPublicationSubmitObserver {
    private static final Logger log = LoggerFactory.getLogger(YagaPublicationSubmitObserver.class);
    private final Page page;
    private final String shop;
    private final Long oldListingId;
    private final Long expectedDraftId;
    private final String expectedDraftSlug;
    private boolean draftFallbackAllowed;
    private boolean responseIdentityMatchesPrepared;
    private boolean draftMismatch;
    private Request publicationRequest;
    private YagaPublicationEvidence evidence;
    private Integer httpStatus;
    private boolean multipleRequests;
    private boolean imageResponseReceived;
    private boolean imageRequestSeen;
    private boolean imageFailure;
    private boolean networkFailure;
    private String imageCaseId;
    private Integer imageHttpStatus;
    private Map<?, ?> observedUi = Map.of();
    private final Consumer<Request> requestListener = this::onRequest;
    private final Consumer<Response> responseListener = this::onResponse;
    private final Consumer<Request> finishedListener = this::onFinished;
    private final Consumer<Request> failedListener = this::onFailed;

    YagaPublicationSubmitObserver(Page page, String shop, Long oldListingId) {
        this(page, shop, oldListingId, null);
    }

    YagaPublicationSubmitObserver(Page page, String shop, Long oldListingId, Long expectedDraftId) {
        this(page, shop, oldListingId, expectedDraftId, null);
    }

    YagaPublicationSubmitObserver(Page page, String shop, Long oldListingId, Long expectedDraftId, String expectedDraftSlug) {
        this.page = page;
        this.shop = shop;
        this.oldListingId = oldListingId;
        this.expectedDraftId = expectedDraftId;
        this.expectedDraftSlug = expectedDraftSlug;
    }

    YagaPublishResult submit(Locator button, double timeoutMs) {
        String before = safeUrl(page.url());
        String afterClick = before;
        String waitReason = "BOUNDED_WAIT_COMPLETED";
        page.onRequest(requestListener);
        page.onResponse(responseListener);
        page.onRequestFinished(finishedListener);
        page.onRequestFailed(failedListener);
        try {
            // Never repeat this click, even if Playwright reports a timeout while dispatching it.
            try {
                button.click(new Locator.ClickOptions().setTimeout(timeoutMs));
                afterClick = safeUrl(page.url());
                page.waitForCondition(() -> {
                            observeUi();
                            return draftMismatch || multipleRequests || networkFailure ||
                                    (evidence != null && evidence.outcome() == CONFIRMED_FAILURE) ||
                                    (imageResponseReceived && evidence != null) ||
                                    ((Boolean.TRUE.equals(observedUi.get("errorToast")) || observedUi.get("caseId") != null) &&
                                            (publicationRequest == null || evidence != null)) ||
                                    (!safeUrl(page.url()).equals(before) &&
                                            (publicationRequest == null || evidence != null));
                        },
                        new Page.WaitForConditionOptions().setTimeout(timeoutMs));
            } catch (RuntimeException exception) {
                waitReason = "CLICK_OR_RESPONSE_WAIT_" + exception.getClass().getSimpleName();
            }
            observeUi();
            Map<?, ?> ui = observedUi;
            String uiCaseId = YagaPublicationResponseParser.safeCaseId((String) ui.get("caseId"));
            String afterWait = safeUrl(page.url());
            if (evidence == null) {
                evidence = YagaPublicationResponseParser.unknown(httpStatus,
                        networkFailure ? "REQUEST_FAILED_WITHOUT_RESPONSE" :
                                publicationRequest == null ? "NO_PUBLICATION_REQUEST_OBSERVED" : "NO_COMPLETE_RESPONSE");
            }
            if (imageRequestSeen && !imageResponseReceived) imageFailure = true;
            boolean uiError = Boolean.TRUE.equals(ui.get("errorToast")) || uiCaseId != null;
            if (draftMismatch || multipleRequests || imageFailure || (uiError && evidence.outcome() == CONFIRMED_SUCCESS)) {
                evidence = new YagaPublicationEvidence(RESULT_UNKNOWN, evidence.httpStatus(),
                        imageCaseId != null ? imageCaseId : evidence.caseId(), evidence.errorCode(),
                        draftMismatch ? "PREPARED_DRAFT_ID_MISMATCH" : multipleRequests ? "MULTIPLE_PUBLICATION_REQUESTS" :
                                imageFailure ? "IMAGE_ORDER_SAVE_FAILED" : "UI_ERROR_AFTER_API_SUCCESS",
                        evidence.externalListingId(), evidence.productSlug(), false);
            }
            if (uiCaseId != null && evidence.caseId() == null) {
                evidence = new YagaPublicationEvidence(evidence.outcome(), evidence.httpStatus(), uiCaseId,
                        evidence.errorCode(), evidence.reason(), evidence.externalListingId(),
                        evidence.productSlug(), evidence.confirmationAllowed());
            }
            // Retain the existing public/transition URL fallback only when there is no contradictory API evidence.
            boolean preparedIdentityKnown = expectedDraftId != null && expectedDraftId > 0 && expectedDraftSlug != null &&
                    expectedDraftSlug.matches("[a-z0-9][a-z0-9_-]{1,149}");
            boolean requestMatchesPrepared = preparedIdentityKnown && publicationRequest != null &&
                    requestId(publicationRequest) == expectedDraftId;
            boolean postSubmitSlugMatchesPrepared = preparedIdentityKnown && matchesPreparedUrl(afterWait);
            boolean validationErrors = ui.get("invalidFieldCount") instanceof Number count && count.intValue() > 0;
            boolean fallbackEligible = evidence.outcome() == RESULT_UNKNOWN && draftFallbackAllowed &&
                    requestMatchesPrepared &&
                    !draftMismatch && !multipleRequests && !networkFailure && !imageFailure && !uiError &&
                    !validationErrors && evidence.caseId() == null &&
                    (postSubmitSlugMatchesPrepared || responseIdentityMatchesPrepared);
            log.info("Yaga publication fallback decision: oldListingId={} fallbackEligible={} preparedDraftId={} " +
                            "preparedDraftSlug={} postSubmitSlugMatchesPrepared={} responseIdentityMatchesPrepared={} requestMatchesPrepared={} " +
                            "responseAllowsDetailValidation={} outcome={} httpStatus={} draftMismatch={} multipleRequests={} " +
                            "networkFailure={} imageFailure={} uiError={} validationErrors={} fallbackStarted=false",
                    oldListingId, fallbackEligible, expectedDraftId, expectedDraftSlug, postSubmitSlugMatchesPrepared,
                    responseIdentityMatchesPrepared, requestMatchesPrepared, draftFallbackAllowed, evidence.outcome(), httpStatus, draftMismatch,
                    multipleRequests, networkFailure, imageFailure, uiError, validationErrors);
            if (fallbackEligible) {
                evidence = new YagaPublicationEvidence(RESULT_UNKNOWN, httpStatus, null, null,
                        "PREPARED_DRAFT_REQUIRES_DETAIL_VALIDATION", expectedDraftId, expectedDraftSlug, true);
            }
            boolean apiIdentity = evidence.externalListingId() != null && evidence.productSlug() != null;
            if (httpStatus == null && !draftMismatch && !multipleRequests && !imageFailure && uiCaseId == null &&
                    !Boolean.TRUE.equals(ui.get("errorToast")) &&
                    new YagaPublishedUrlResolver()
                            .isResolvable(afterWait, shop)) {
                evidence = new YagaPublicationEvidence(RESULT_UNKNOWN, null, null, null,
                        "NAVIGATION_REQUIRES_DETAIL_VALIDATION", null, null, true);
            }
            log.info("Yaga publication submit evidence: oldListingId={} clickAttempted=true requestObserved={} " +
                            "requestMethod={} requestUrl={} preparedDraftId={} httpStatus={} outcome={} reason={} caseId={} errorCode={} " +
                            "externalListingId={} productSlug={} imageHttpStatus={} imageCaseId={} " +
                            "urlBefore={} urlAfterClick={} urlAfterWait={} waitReason={} " +
                            "errorToast={} successToast={} invalidFieldCount={} buttonDisabled={} uiMessageKey={}",
                    oldListingId, publicationRequest != null, publicationRequest == null ? null : "PATCH",
                    publicationRequest == null ? null : safeUrl(publicationRequest.url()),
                    expectedDraftId,
                    evidence.httpStatus(), evidence.outcome(), evidence.reason(), evidence.caseId(), evidence.errorCode(),
                    evidence.externalListingId(), evidence.productSlug(), imageHttpStatus, imageCaseId,
                    before, afterClick, afterWait, waitReason, ui.get("errorToast"), ui.get("successToast"),
                    ui.get("invalidFieldCount"), ui.get("buttonDisabled"), ui.get("messageKey"));

            String url = apiIdentity
                    ? "https://www.yaga.ee/" + shop + "/toode/" + evidence.productSlug() : afterWait;
            return new YagaPublishResult(true,
                    evidence.confirmationAllowed() && !fallbackEligible ? YagaPublicationStatus.PUBLISHED : YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                    url, apiIdentity || evidence.confirmationAllowed() ? shop : null,
                    evidence.productSlug(), Instant.now(), evidence);
        } finally {
            page.offRequest(requestListener);
            page.offResponse(responseListener);
            page.offRequestFinished(finishedListener);
            page.offRequestFailed(failedListener);
        }
    }

    private void onRequest(Request request) {
        if (isImageOrderRequest(request)) imageRequestSeen = true;
        if (!isPublicationRequest(request)) return;
        if (expectedDraftId != null && expectedDraftId != requestId(request)) draftMismatch = true;
        if (publicationRequest == null) publicationRequest = request;
        else if (publicationRequest != request) multipleRequests = true;
    }

    private void onResponse(Response response) {
        if (response.request() == publicationRequest) httpStatus = response.status();
    }

    private void onFinished(Request request) {
        try {
            if (request == publicationRequest) {
                Response response = request.response();
                String body = response.text();
                evidence = YagaPublicationResponseParser.parse(response.status(), body,
                        requestId(request), shop);
                draftFallbackAllowed = YagaPublicationResponseParser.allowsDraftValidation(
                        response.status(), body, requestId(request), expectedDraftSlug, shop);
                responseIdentityMatchesPrepared = YagaPublicationResponseParser.matchesPreparedIdentity(
                        body, expectedDraftId, expectedDraftSlug);
                log.info("Yaga publication classifier: oldListingId={} checks={}", oldListingId,
                        YagaPublicationResponseParser.checks(body, requestId(request), shop));
                log.info("Yaga publication response: oldListingId={} httpStatus={} outcome={} caseId={} " +
                                "errorCode={} reason={} externalListingId={} productSlug={} responseShape={}", oldListingId,
                        evidence.httpStatus(), evidence.outcome(), evidence.caseId(), evidence.errorCode(),
                        evidence.reason(), evidence.externalListingId(), evidence.productSlug(),
                        YagaPublicationResponseParser.shape(body));
            } else if (isImageOrderRequest(request)) {
                Response response = request.response();
                imageHttpStatus = response.status();
                String body = response.text();
                // Parse the same envelope for explicit API errors, but images are not publication identity.
                YagaPublicationEvidence image = YagaPublicationResponseParser.parse(
                        response.status(), body, requestId(publicationRequest), shop);
                imageFailure = !YagaPublicationResponseParser.imageOrderSaved(response.status(), body);
                imageCaseId = image.caseId();
                imageResponseReceived = true;
                log.info("Yaga publication image-order response: oldListingId={} httpStatus={} saved={} caseId={} responseShape={}",
                        oldListingId, imageHttpStatus, !imageFailure, imageCaseId, YagaPublicationResponseParser.shape(body));
            }
        } catch (RuntimeException ignored) {
            if (request == publicationRequest) evidence = YagaPublicationResponseParser.unknown(httpStatus, "RESPONSE_UNREADABLE");
            else if (isImageOrderRequest(request)) { imageFailure = true; imageResponseReceived = true; }
        }
    }

    private void onFailed(Request request) {
        if (request == publicationRequest) networkFailure = true;
        else if (isImageOrderRequest(request)) { imageFailure = true; imageResponseReceived = true; }
    }

    private boolean matchesPreparedUrl(String url) {
        for (String host : new String[]{"https://www.yaga.ee", "https://yaga.ee"}) {
            if (url.equals(host + "/muuk/lisa-toode/" + expectedDraftSlug) ||
                    url.equals(host + "/" + shop + "/toode/" + expectedDraftSlug)) return true;
        }
        return false;
    }

    static boolean isPublicationRequest(Request request) {
        return "PATCH".equals(request.method()) && safeUrl(request.url())
                .matches("https://(?:www\\.)?yaga\\.ee/api/product/[1-9][0-9]*/?");
    }

    private boolean isImageOrderRequest(Request request) {
        return publicationRequest != null && "POST".equals(request.method()) && safeUrl(request.url())
                .equals("https://www.yaga.ee/api/product/" + requestId(publicationRequest) + "/images");
    }

    private static long requestId(Request request) {
        String path = URI.create(request.url()).getPath().replaceAll("/$", "");
        return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
    }

    static String safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) ||
                    !("www.yaga.ee".equals(uri.getHost()) || "yaga.ee".equals(uri.getHost()))) return "OTHER_ORIGIN";
            if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return "OTHER_ORIGIN";
            // Diagnostic URLs must remain encoded: decoded paths cannot safely be parsed again.
            return "https://" + uri.getHost() + uri.getRawPath();
        } catch (RuntimeException ignored) {
            log.warn("Yaga observer ignored malformed URL: sanitizedPath={} reason=INVALID_URI_SYNTAX",
                    diagnosticPath(value));
            return "UNAVAILABLE";
        }
    }

    private static String diagnosticPath(String value) {
        if (value == null) return "UNAVAILABLE";
        String path;
        if (value.startsWith("https://www.yaga.ee/")) path = value.substring("https://www.yaga.ee".length());
        else if (value.startsWith("https://yaga.ee/")) path = value.substring("https://yaga.ee".length());
        else return "UNTRUSTED_OR_RELATIVE_URL";
        path = path.split("[?#]", 2)[0];
        // Static asset paths contain public build/route names; arbitrary paths may contain secrets.
        if (!path.startsWith("/_next/static/")) return "YAGA_PATH_REDACTED";
        path = path.replaceAll("[^A-Za-z0-9/_.%\\[\\] -]", "_");
        return path.substring(0, Math.min(path.length(), 200));
    }

    private Map<?, ?> inspectUi() {
        try {
            Object result = page.evaluate("""
                    () => {
                      const alerts = [...document.querySelectorAll('.notistack-case-id, [role="alert"]')];
                      const codes = alerts.map(e => e.textContent.match(/\\bEE-A-[A-Za-z0-9-]{1,100}\\b/)?.[0]).filter(Boolean);
                      const button = [...document.querySelectorAll('button')].find(e => e.textContent.trim() === 'Valmis');
                      return {caseId: codes[0] || null,
                        errorToast: !!document.querySelector('.notistack-MuiContent-error'),
                        successToast: !!document.querySelector('.notistack-MuiContent-success'),
                        invalidFieldCount: document.querySelectorAll('[aria-invalid="true"], input:invalid, textarea:invalid').length,
                        messageKey: alerts.some(e => e.textContent.includes('Miski l\\u00e4ks valesti')) ? 'GENERAL_ERROR' : null,
                        buttonDisabled: button ? button.disabled : null};
                    }
                    """);
            return result instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException ignored) { return Map.of(); }
    }

    private void observeUi() {
        Map<?, ?> snapshot = inspectUi();
        // Keep transient toast evidence even if it disappears before the network wait finishes.
        if (!Boolean.TRUE.equals(observedUi.get("errorToast")) && observedUi.get("caseId") == null) {
            observedUi = snapshot;
        }
    }
}
