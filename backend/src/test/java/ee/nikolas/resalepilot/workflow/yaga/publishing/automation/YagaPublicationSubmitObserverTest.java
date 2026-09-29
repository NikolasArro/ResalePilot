package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class YagaPublicationSubmitObserverTest {
    private static final String SUCCESS = """
            {"status":"success","data":{"id":123,"slug":"new-product","status":"published",
            "shop":{"slug":"w-a-k-a"},"description":"PRIVATE","token":"SECRET"}}
            """;
    private final Page page = mock(Page.class);
    private final Locator button = mock(Locator.class);
    private final AtomicReference<Consumer<Request>> requested = new AtomicReference<>();
    private final AtomicReference<Consumer<Request>> finished = new AtomicReference<>();
    private final AtomicReference<Consumer<Request>> failed = new AtomicReference<>();
    private final AtomicReference<Consumer<Response>> responded = new AtomicReference<>();

    YagaPublicationSubmitObserverTest() {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode?secret=SECRET");
        when(page.evaluate(anyString())).thenReturn(Map.of());
        doAnswer(a -> { requested.set(a.getArgument(0)); return null; }).when(page).onRequest(any());
        doAnswer(a -> { finished.set(a.getArgument(0)); return null; }).when(page).onRequestFinished(any());
        doAnswer(a -> { failed.set(a.getArgument(0)); return null; }).when(page).onRequestFailed(any());
        doAnswer(a -> { responded.set(a.getArgument(0)); return null; }).when(page).onResponse(any());
    }

    @Test
    void successfulResponseSuppliesIdentityEvenWithoutSpaNavigation() {
        onClick(() -> emit("PATCH", "/api/product/123", 200, SUCCESS));
        YagaPublishResult result = submit();
        assertThat(result.evidence().outcome()).isEqualTo(CONFIRMED_SUCCESS);
        assertThat(result.evidence().externalListingId()).isEqualTo(123L);
        assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/new-product");
        assertThat(result.evidence().toString()).doesNotContain("SECRET", "PRIVATE");
        verifySingleClickAndCleanup();
    }

    @Test
    void definiteApiErrorCapturesCaseIdWithoutSensitiveMessage(CapturedOutput output) {
        onClick(() -> emit("PATCH", "/api/product/123", 400, """
                {"status":"error","data":{"caseId":"EE-A-123-abc","code":"NO_ENABLED_COURIER_OPTIONS",
                "message":"private email or token SECRET"}}
                """));
        var result = submit();
        assertThat(result.evidence().outcome()).isEqualTo(CONFIRMED_FAILURE);
        assertThat(result.evidence().caseId()).isEqualTo("EE-A-123-abc");
        assertThat(result.evidence().errorCode()).isEqualTo("NO_ENABLED_COURIER_OPTIONS");
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        assertThat(result.evidence().toString()).doesNotContain("SECRET", "email");
        assertThat(output).contains("httpStatus=400", "caseId=EE-A-123-abc", "data.message=STRING")
                .doesNotContain("SECRET", "private email");
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 422, 429, 500, 503})
    void httpErrorNeverEnablesConfirmationOrRetry(int status) {
        onClick(() -> emit("PATCH", "/api/product/123", status, "{\"status\":\"error\",\"data\":{}}"));
        assertThat(submit().evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @Test
    void networkFailureBeforeResponseIsUnknownAndDoesNotRetry() {
        onClick(() -> {
            Request request = request("PATCH", "/api/product/123");
            requested.get().accept(request);
            failed.get().accept(request);
        });
        var evidence = submit().evidence();
        assertThat(evidence.outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(evidence.reason()).isEqualTo("REQUEST_FAILED_WITHOUT_RESPONSE");
        verifySingleClickAndCleanup();
    }

    @Test
    void clickTimeoutIsUnknownAndDoesNotRetry() {
        doThrow(new TimeoutError("sensitive exception SECRET")).when(button).click(any(Locator.ClickOptions.class));
        assertThat(submit().evidence().outcome()).isEqualTo(RESULT_UNKNOWN);
        verifySingleClickAndCleanup();
    }

    @Test
    void responseTimeoutIsUnknownAndDoesNotRetry() {
        onClick(() -> requested.get().accept(request("PATCH", "/api/product/123")));
        doThrow(new TimeoutError("timeout")).when(page).waitForCondition(any(BooleanSupplier.class), any());
        assertThat(submit().evidence().reason()).isEqualTo("NO_COMPLETE_RESPONSE");
        verifySingleClickAndCleanup();
    }

    @Test
    void validationErrorsBeforeRequestRemainUnknownWithoutRetry() {
        when(page.evaluate(anyString())).thenReturn(Map.of("invalidFieldCount", 1, "errorToast", true));
        assertThat(submit().evidence().reason()).isEqualTo("NO_PUBLICATION_REQUEST_OBSERVED");
        verifySingleClickAndCleanup();
    }

    @Test
    void imageSaveErrorCannotBeMistakenForFailedPublicationOrPermitCompletion() {
        onClick(() -> {
            emit("PATCH", "/api/product/123", 200, SUCCESS);
            emit("POST", "/api/product/123/images", 500,
                    "{\"status\":\"error\",\"data\":{\"caseId\":\"EE-A-image-error\"}}");
        });
        var result = submit();
        var evidence = result.evidence();
        assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/new-product");
        assertThat(result.shopSlug()).isEqualTo("w-a-k-a");
        assertThat(evidence.outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(evidence.externalListingId()).isEqualTo(123L);
        assertThat(evidence.caseId()).isEqualTo("EE-A-image-error");
        assertThat(evidence.reason()).isEqualTo("IMAGE_ORDER_SAVE_FAILED");
        assertThat(evidence.confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @Test
    void extractsToastCaseIdWithoutLoggingToastText() {
        when(page.evaluate(anyString())).thenReturn(Map.of("caseId", "EE-A-toast-123", "errorToast", true));
        assertThat(submit().evidence().caseId()).isEqualTo("EE-A-toast-123");
    }

    @Test
    void existingNavigationFallbackStillRequiresDetailValidation() {
        onClick(() -> when(page.url()).thenReturn("https://www.yaga.ee/w-a-k-a/toode/new-product"));
        var result = submit();
        assertThat(result.evidence().confirmationAllowed()).isTrue();
        assertThat(result.evidence().reason()).isEqualTo("NAVIGATION_REQUIRES_DETAIL_VALIDATION");
    }

    @Test
    void excludesDraftCreationUploadsAndOtherOrigins() {
        assertThat(YagaPublicationSubmitObserver.isPublicationRequest(request("POST", "/api/product"))).isFalse();
        assertThat(YagaPublicationSubmitObserver.isPublicationRequest(request("POST", "/api/product/123/images"))).isFalse();
        Request other = request("PATCH", "/api/product/123");
        when(other.url()).thenReturn("https://other.example/api/product/123");
        assertThat(YagaPublicationSubmitObserver.isPublicationRequest(other)).isFalse();
        assertThat(YagaPublicationSubmitObserver.safeUrl("https://www.yaga.ee/path?token=SECRET#SECRET"))
                .isEqualTo("https://www.yaga.ee/path");
    }

    @Test
    void mismatchingApiIdentityOrShopCannotPass() {
        assertThat(YagaPublicationResponseParser.parse(200, SUCCESS, 456, "w-a-k-a").confirmationAllowed()).isFalse();
        assertThat(YagaPublicationResponseParser.parse(200, SUCCESS, 123, "nik-ar").confirmationAllowed()).isFalse();
        assertThat(YagaPublicationResponseParser.parse(200, "<html>SECRET</html>", 123, "w-a-k-a")
                .outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(YagaPublicationResponseParser.parse(503, "<html>SECRET</html>", 123, "w-a-k-a")
                .outcome()).isEqualTo(CONFIRMED_FAILURE);
        assertThat(YagaPublicationResponseParser.imageOrderSaved(200, "<html>error</html>")).isFalse();
        assertThat(YagaPublicationResponseParser.imageOrderSaved(200, "{\"status\":\"success\",\"data\":[]}")).isTrue();
    }

    @Test
    void publicationForDifferentPreparedDraftCannotBeConfirmed() {
        onClick(() -> emit("PATCH", "/api/product/123", 200, SUCCESS));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L, 456L).submit(button, 1000);
        assertThat(result.evidence().reason()).isEqualTo("PREPARED_DRAFT_ID_MISMATCH");
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    private void onClick(Runnable action) {
        doAnswer(a -> { action.run(); return null; }).when(button).click(any(Locator.ClickOptions.class));
    }

    @Test
    void incompleteSuccessUsesPreparedIdentityOnlyForStrictDetailValidation(CapturedOutput output) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode/fn2o3ca80kk");
        onClick(() -> {
            emit("PATCH", "/api/product/31335252", 200,
                    "{\"status\":\"success\",\"data\":{\"id\":31335252,\"slug\":\"fn2o3ca80kk\"}}");
            emit("POST", "/api/product/31335252/images", 200, "{\"status\":\"success\",\"data\":[]}");
        });
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 370L, 31335252L, "fn2o3ca80kk")
                .submit(button, 1000);
        assertThat(result.evidence().outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(result.evidence().confirmationAllowed()).isTrue();
        assertThat(result.evidence().reason()).isEqualTo("PREPARED_DRAFT_REQUIRES_DETAIL_VALIDATION");
        assertThat(result.evidence().externalListingId()).isEqualTo(31335252L);
        assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/fn2o3ca80kk");
        assertThat(output).contains("dataStatus=MISSING", "published=false", "idMatch=true", "requestedId=31335252");
        verifySingleClickAndCleanup();
    }

    @Test
    void wrongPreparedSlugCannotUseFallback() {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode/wrong-slug");
        onClick(() -> emit("PATCH", "/api/product/123", 200, "{\"status\":\"success\",\"data\":{}}"));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 370L, 123L, "expected-slug")
                .submit(button, 1000);
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"success\",\"data\":{\"id\":31335741,\"slug\":\"46qt08oto7s\",\"status\":\"unrecognized_remote_status\"}}",
            "{\"status\":\"success\",\"data\":{}}"})
    void unknownAcknowledgementUsesIntermediatePreparedUrlWithoutClaimingSuccess(String body, CapturedOutput output) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode/46qt08oto7s");
        when(page.evaluate(anyString())).thenReturn(Map.of("errorToast", false, "invalidFieldCount", 0));
        onClick(() -> {
            emit("PATCH", "/api/product/31335741", 200, body);
            emit("POST", "/api/product/31335741/images", 200, "{\"status\":\"success\",\"data\":[]}");
        });
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 428L, 31335741L, "46qt08oto7s")
                .submit(button, 1000);
        assertThat(result.status()).isEqualTo(ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN);
        assertThat(result.evidence().outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(result.evidence().confirmationAllowed()).isTrue();
        assertThat(result.evidence().externalListingId()).isEqualTo(31335741L);
        assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/46qt08oto7s");
        assertThat(output).contains("fallbackEligible=true", "preparedDraftId=31335741", "preparedDraftSlug=46qt08oto7s",
                "postSubmitSlugMatchesPrepared=true", "requestMatchesPrepared=true");
        verifySingleClickAndCleanup();
    }

    @Test
    void missingPreparedSlugCannotBeInferredFromBrowserUrl(CapturedOutput output) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode/46qt08oto7s");
        onClick(() -> emit("PATCH", "/api/product/31335741", 200, "{\"status\":\"success\",\"data\":[]}"));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 428L, 31335741L).submit(button, 1000);
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        assertThat(output).contains("fallbackEligible=false", "preparedDraftSlug=null", "postSubmitSlugMatchesPrepared=false");
        verifySingleClickAndCleanup();
    }

    @Test
    void matchingPatchIdentityStartsValidationFromBareFormUrl(CapturedOutput output) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        onClick(() -> {
            emit("PATCH", "/api/product/31340437", 200,
                    "{\"status\":\"success\",\"data\":{\"id\":31340437,\"slug\":\"rdfi4fa1l24\",\"status\":\"unrecognized_remote_status\"}}");
            emit("POST", "/api/product/31340437/images", 200, "{\"status\":\"success\",\"data\":[]}");
        });
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 427L, 31340437L, "rdfi4fa1l24")
                .submit(button, 1000);
        assertThat(result.evidence().outcome()).isEqualTo(RESULT_UNKNOWN);
        assertThat(result.evidence().confirmationAllowed()).isTrue();
        assertThat(result.evidence().externalListingId()).isEqualTo(31340437L);
        assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/rdfi4fa1l24");
        assertThat(output).contains("fallbackEligible=true", "postSubmitSlugMatchesPrepared=false", "responseIdentityMatchesPrepared=true");
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":31340438,\"slug\":\"rdfi4fa1l24\"}",
            "{\"id\":31340437,\"slug\":\"other-slug\"}", "{\"id\":31340437}", "{\"slug\":\"rdfi4fa1l24\"}"})
    void incompleteOrConflictingPatchIdentityCannotReplaceUrlEvidence(String data) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        onClick(() -> emit("PATCH", "/api/product/31340437", 200, "{\"status\":\"success\",\"data\":" + data + "}"));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 427L, 31340437L, "rdfi4fa1l24")
                .submit(button, 1000);
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 500})
    void matchingIdentityNeverBypassesExplicitFailure(int status) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        onClick(() -> emit("PATCH", "/api/product/31340437", status,
                "{\"status\":\"error\",\"data\":{\"id\":31340437,\"slug\":\"rdfi4fa1l24\",\"caseId\":\"EE-A-test\"}}"));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 427L, 31340437L, "rdfi4fa1l24")
                .submit(button, 1000);
        assertThat(result.evidence().outcome()).isEqualTo(CONFIRMED_FAILURE);
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"error\"}", "{\"result\":\"failure\"}",
            "{\"data\":[{\"caseId\":\"EE-A-test\"}]}", "{\"success\":false}"})
    void explicitErrorsCannotUseIntermediateUrlFallback(String body) {
        when(page.url()).thenReturn("https://www.yaga.ee/muuk/lisa-toode/46qt08oto7s");
        onClick(() -> emit("PATCH", "/api/product/31335741", 200, body));
        var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 428L, 31335741L, "46qt08oto7s").submit(button, 1000);
        assertThat(result.evidence().confirmationAllowed()).isFalse();
        verifySingleClickAndCleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":456}", "{\"slug\":\"wrong-slug\"}", "{\"status\":\"draft\"}",
            "{\"caseId\":\"EE-A-test\"}", "{\"deletedAt\":\"now\"}", "{\"hiddenAt\":\"now\"}",
            "{\"shop\":{\"slug\":\"nik-ar\"}}", "{\"errorCode\":\"UNKNOWN_ERROR\"}"})
    void conflictingResponseCannotUseDraftFallback(String data) {
        assertThat(YagaPublicationResponseParser.allowsDraftValidation(200,
                "{\"status\":\"success\",\"data\":" + data + "}", 123, "expected-slug", "w-a-k-a")).isFalse();
    }

    @Test
    void explicitErrorNeverUsesDraftFallback() {
        for (int status : new int[]{200, 400, 500}) {
            String body = "{\"status\":\"error\",\"data\":{\"caseId\":\"EE-A-test\"}}";
            assertThat(YagaPublicationResponseParser.parse(status, body, 123, "w-a-k-a").outcome())
                    .isEqualTo(CONFIRMED_FAILURE);
            assertThat(YagaPublicationResponseParser.allowsDraftValidation(status, body, 123, "slug", "w-a-k-a"))
                    .isFalse();
        }
    }

    private Request request(String method, String path) {
        Request request = mock(Request.class);
        when(request.method()).thenReturn(method);
        when(request.url()).thenReturn("https://www.yaga.ee" + path);
        return request;
    }

    private void emit(String method, String path, int status, String body) {
        Request request = request(method, path);
        Response response = mock(Response.class);
        when(request.response()).thenReturn(response);
        when(response.request()).thenReturn(request);
        when(response.status()).thenReturn(status);
        when(response.text()).thenReturn(body);
        requested.get().accept(request);
        responded.get().accept(response);
        finished.get().accept(request);
    }

    private YagaPublishResult submit() {
        return new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L).submit(button, 15_000);
    }

    private void verifySingleClickAndCleanup() {
        verify(button, times(1)).click(any(Locator.ClickOptions.class));
        verify(page).offRequest(any());
        verify(page).offResponse(any());
        verify(page).offRequestFinished(any());
        verify(page).offRequestFailed(any());
    }
}
