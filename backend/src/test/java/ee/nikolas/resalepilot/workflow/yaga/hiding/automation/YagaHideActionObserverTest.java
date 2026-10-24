package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import com.microsoft.playwright.*;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideDiagnostic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class YagaHideActionObserverTest {
    @Test void capturesARealLocalBrowserClickAndRemovesDomListener() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))) {
            Page page = browser.newPage();
            // Every request is fulfilled locally; no Yaga traffic is sent.
            page.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                    .setContentType("text/html").setBody(route.request().url().contains("/api/product/")
                            ? "{}" : "<button onclick=\"fetch('/api/product/123',{method:'PATCH'})\">Hide</button>")));
            page.navigate("https://www.yaga.ee/fixture");
            var diagnostic = new YagaHideDiagnostic();
            var observer = new YagaHideActionObserver(page, "123", diagnostic);
            Locator button = page.locator("button");
            observer.start(button);
            button.click();
            page.waitForCondition(() -> diagnostic.summary().contains("HTTP_SUCCESS_FINISHED"));
            diagnostic.closeObservation();
            assertThat(diagnostic.summary()).contains("CLICK_DISPATCHED", "status=200");
            assertThat(page.evaluate("() => typeof window.__rpHideListener")).isEqualTo("undefined");
        }
    }

    @Test void capturesHeadersWithoutFinishThenCompletionAndCleansEveryListener() {
        Page page = mock(Page.class); Locator button = mock(Locator.class);
        var diagnostic = new YagaHideDiagnostic();
        var observer = new YagaHideActionObserver(page, "123", diagnostic);
        observer.start(button);
        ArgumentCaptor<Consumer<Request>> requests = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Response>> responses = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Request>> finished = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onRequest(requests.capture()); verify(page).onResponse(responses.capture());
        verify(page).onRequestFinished(finished.capture());
        Request r = mock(Request.class); when(r.url()).thenReturn("https://www.yaga.ee/api/product/123?token=SECRET");
        when(r.method()).thenReturn("PATCH");
        Response response = mock(Response.class); when(response.request()).thenReturn(r); when(response.status()).thenReturn(200);
        requests.getValue().accept(r);
        assertThat(diagnostic.summary()).contains("REQUEST_WITHOUT_RESPONSE");
        responses.getValue().accept(response);
        assertThat(diagnostic.summary()).contains("RESPONSE_WITHOUT_FINISH");
        finished.getValue().accept(r);
        assertThat(diagnostic.summary()).contains("HTTP_SUCCESS_FINISHED", "status=200").doesNotContain("SECRET", "token=", "https://");
        when(page.evaluate(anyString())).thenReturn(Map.of("seen", true, "ms", 7));
        observer.close();
        assertThat(diagnostic.summary()).contains("CLICK_DISPATCHED:domMs=7");
        verify(page).offRequest(any()); verify(page).offResponse(any());
        verify(page).offRequestFinished(any()); verify(page).offRequestFailed(any());
        observer.close(); verify(page, times(1)).offRequest(any());
    }

    @Test void noRequestFailureReasonsAndRetentionAreBoundedAndSanitized() {
        Page page = mock(Page.class);
        var diagnostic = new YagaHideDiagnostic();
        var observer = new YagaHideActionObserver(page, "123", diagnostic);
        observer.start(mock(Locator.class));
        assertThat(diagnostic.summary()).contains("NO_RELEVANT_REQUEST");
        ArgumentCaptor<Consumer<Request>> requests = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Request>> failures = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onRequest(requests.capture()); verify(page).onRequestFailed(failures.capture());
        Request r = mock(Request.class); when(r.method()).thenReturn("POST");
        when(r.url()).thenReturn("https://www.yaga.ee/api/product/123/hide/SECRET?email=PRIVATE");
        when(r.failure()).thenReturn("net::ERR_TIMED_OUT");
        for (int i = 0; i < 1000; i++) requests.getValue().accept(r);
        failures.getValue().accept(r);
        assertThat(diagnostic.summary()).contains("REQUEST_FAILED", "eventsDropped=")
                .doesNotContain("SECRET", "PRIVATE", "https://").hasSizeLessThanOrEqualTo(12_000);
        when(r.failure()).thenReturn("SECRET"); failures.getValue().accept(r);
        diagnostic.actionFailed(new TimeoutError("SECRET"));
        assertThat(diagnostic.summary()).contains("BROWSER_ACTION_EXCEPTION").doesNotContain("SECRET");
        doThrow(new IllegalStateException()).when(page).offRequest(any());
        observer.close(); verify(page).offResponse(any()); verify(page).offRequestFailed(any());
    }
}
