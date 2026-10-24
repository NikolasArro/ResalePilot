package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class YagaDraftImageSaveDiagnosticTest {
    private final Page page = mock(Page.class);
    private Consumer<Request> started, finished, failed;
    private Consumer<Response> response;
    private int waits;

    private YagaDraftPreparationObserver observer() {
        when(page.evaluate(anyString())).thenReturn(false);
        doAnswer(a -> { started = a.getArgument(0); return null; }).when(page).onRequest(any());
        doAnswer(a -> { finished = a.getArgument(0); return null; }).when(page).onRequestFinished(any());
        doAnswer(a -> { failed = a.getArgument(0); return null; }).when(page).onRequestFailed(any());
        doAnswer(a -> { response = a.getArgument(0); return null; }).when(page).onResponse(any());
        doAnswer(a -> {
            if (!((BooleanSupplier) a.getArgument(0)).getAsBoolean()) throw new TimeoutError("SECRET");
            return null;
        }).when(page).waitForCondition(any(BooleanSupplier.class), any());
        var observer = new YagaDraftPreparationObserver(page, 649L, "w-a-k-a");
        Request init = request("POST", "/api/product");
        Response r = reply(init, "{\"status\":\"success\",\"data\":{\"id\":31717688,\"slug\":\"draft-one\"}}");
        started.accept(init); response.accept(r); finished.accept(init);
        return observer;
    }

    @Test void delayedSuccessfulCompletionRetainsPendingUntilFinished() {
        try (var observer = observer()) {
            Request image = request("POST", "/api/product/31717688/images?token=SECRET");
            Response r = reply(image, "{\"status\":\"success\",\"data\":[]}");
            started.accept(image); response.accept(r);
            doAnswer(a -> {
                var condition = (BooleanSupplier) a.getArgument(0);
                if (++waits == 2) {
                    assertThat(condition.getAsBoolean()).isFalse();
                    assertThat(observer.imageSaveDiagnostic()).contains("pendingImages=1", "imageResponseObserved=true");
                    finished.accept(image); // response headers arrived earlier; body finishes during readiness wait
                }
                assertThat(condition.getAsBoolean()).isTrue();
                return null;
            }).when(page).waitForCondition(any(BooleanSupplier.class), any());
            assertThat(observer.requireReady(15_000).id()).isEqualTo(31717688L);
            assertThat(observer.imageSaveDiagnostic()).contains("imagesSaved=true", "pendingImages=0", "PARSED_SAVED:r1")
                    .doesNotContain("SECRET", "token=", "https://");
        }
        cleanupVerified();
    }

    @Test void headersWithoutCompletionFreezeTimeoutStateAndDoNotChangeReadiness() {
        try (var observer = observer()) {
            Request image = request("POST", "/api/product/31717688/images");
            Response r = reply(image, "{\"status\":\"success\",\"data\":[]}");
            started.accept(image); response.accept(r);
            var exception = catchThrowableOfType(() -> observer.requireReady(15_000), YagaPublishingFormException.class);
            String diagnostic = exception.getDiagnostics().draftImageSaveDiagnostic();
            assertThat(diagnostic).contains("phase=IMAGE_SAVE", "imagesSaved=false", "pendingImages=1",
                    "imageResponseObserved=true", "RESPONSE:r1:POST:IMAGE_SAVE:tracked=true:status=200", "sinceLastEventMs=");
            finished.accept(image); // too late: existing failure stays latched and stored snapshot stays unchanged
            assertThat(exception.getDiagnostics().draftImageSaveDiagnostic()).isEqualTo(diagnostic);
            assertThatThrownBy(() -> observer.requireReady(15_000)).hasMessageContaining("DRAFT_PREPARATION_TIMEOUT");
        }
        cleanupVerified();
    }

    @Test void multiplePendingRequestsAndUnmatchedResponsesRemainVisible() {
        try (var observer = observer()) {
            Request first = request("POST", "/api/product/31717688/images");
            Request second = request("POST", "/api/product/31717688/images");
            started.accept(first); started.accept(second);
            finished.accept(withReply(first));
            response.accept(reply(request("POST", "/api/product/31717688/images"), "{}"));
            assertThatThrownBy(() -> observer.requireReady(15_000)).hasMessageContaining("DRAFT_PREPARATION_TIMEOUT");
            assertThat(observer.imageSaveDiagnostic()).contains("imagesSaved=true", "pendingImages=1", "tracked=false", "FINISHED:r1", "RESPONSE:r3");
        }
    }

    @Test void failedRequestHasCorrelatedEventWithoutChangingFailureReason() {
        try (var observer = observer()) {
            Request image = request("POST", "/api/product/31717688/images");
            started.accept(image); failed.accept(image);
            assertThatThrownBy(() -> observer.requireReady(15_000)).hasMessageContaining("DRAFT_REQUEST_FAILED");
            assertThat(observer.imageSaveDiagnostic()).contains("FAILED:r1", "pendingImages=1", "imageResponseObserved=false");
        }
    }

    @Test void boundedTelemetryRedactsUrlsAndRetainsLastEventAfterOverflow() {
        var diagnostic = new YagaDraftImageSaveDiagnostic();
        Request request = request("PUT", "/api/product/SECRET/images/PRIVATE?token=SECRET");
        for (int i = 0; i < 1000; i++) diagnostic.event("REQUEST", request, null, false);
        diagnostic.event("RESPONSE", request, 200, false);
        String summary = diagnostic.summary(false, 1);
        assertThat(summary).contains("OTHER_IMAGE_API", "eventsDropped=937", "lastEvent=RESPONSE", "tracked=false")
                .doesNotContain("SECRET", "PRIVATE", "token=", "https://").hasSizeLessThanOrEqualTo(12_000);
    }

    @Test void noImageResponseOrRequestIsDistinguishableFromPendingUpload() {
        try (var observer = observer()) {
            assertThatThrownBy(() -> observer.requireReady(15_000)).hasMessageContaining("DRAFT_PREPARATION_TIMEOUT");
            assertThat(observer.imageSaveDiagnostic()).contains("pendingImages=0", "imagesSaved=false", "imageResponseObserved=false", "lastEvent=NONE");
        }
    }

    @Test void partialRegistrationFailureAndRemovalFailureStillCleanUp() {
        doThrow(new IllegalStateException("registration")).when(page).onResponse(any());
        doThrow(new IllegalStateException("closed")).when(page).offRequest(any());
        assertThatThrownBy(() -> new YagaDraftPreparationObserver(page, 649L, "w-a-k-a"))
                .hasMessage("registration");
        verify(page).offRequest(any()); verify(page).offResponse(any());
        verify(page).offRequestFinished(any()); verify(page).offRequestFailed(any());
    }

    @Test void diagnosticSurvivesImmutableCopiesUsedByFailureEnrichment() {
        var diagnostic = ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics
                .failureMetadata(null, null, null, null).withDraftImageSaveDiagnostic("pendingImages=1");
        var copy = diagnostic.withFailureMetadata("OPEN_FORM", "Exception", "Exception", "CODE")
                .withClothingSelection(null).withPriceFill(null).withOpenFormDiagnostic(null);
        assertThat(copy.draftImageSaveDiagnostic()).isEqualTo("pendingImages=1");
        assertThat(copy.preparationFailureSummary()).isEqualTo("draftImageSave={pendingImages=1}");
    }

    private Request withReply(Request request) { reply(request, "{\"status\":\"success\",\"data\":[]}"); return request; }
    private Request request(String method, String path) {
        Request r = mock(Request.class); when(r.url()).thenReturn("https://www.yaga.ee" + path); when(r.method()).thenReturn(method); return r;
    }
    private Response reply(Request request, String body) {
        Response r = mock(Response.class); when(r.request()).thenReturn(request); when(r.status()).thenReturn(200);
        when(r.text()).thenReturn(body); when(request.response()).thenReturn(r); return r;
    }
    private void cleanupVerified() {
        verify(page).offRequest(started); verify(page).offResponse(response);
        verify(page).offRequestFinished(finished); verify(page).offRequestFailed(failed);
    }
}
