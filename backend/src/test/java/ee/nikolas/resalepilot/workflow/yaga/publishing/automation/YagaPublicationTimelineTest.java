package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class YagaPublicationTimelineTest {
    @Test void capturesOnlySafeCategoriesAndBoundsRetention() {
        Page page = mock(Page.class);
        YagaPublicationTimeline timeline = new YagaPublicationTimeline(page);
        timeline.start();
        timeline.clickStarted();
        Consumer<String> errors = listener(page, "error");
        errors.accept("SECRET token=abc private@example.com");
        ArgumentCaptor<Consumer<ConsoleMessage>> consoles = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onConsoleMessage(consoles.capture());
        ConsoleMessage console = mock(ConsoleMessage.class);
        when(console.type()).thenReturn("error");
        consoles.getValue().accept(console);
        verify(console, never()).text();
        Request r = mock(Request.class);
        when(r.url()).thenReturn("https://www.yaga.ee/api/product/SECRET?token=SECRET");
        when(r.method()).thenReturn("PUT");
        when(r.failure()).thenReturn("net::ERR_TIMED_OUT");
        ArgumentCaptor<Consumer<Request>> failures = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onRequestFailed(failures.capture());
        failures.getValue().accept(r);
        when(page.evaluate(contains("snapshot()"))).thenReturn(Map.of("events", List.of(
                Map.of("kind", "submit", "ms", 2), Map.of("kind", "invalid", "ms", 3),
                Map.of("kind", "inline-validation", "ms", 4), Map.of("kind", "SECRET", "ms", 5))));
        assertThat(timeline.summary()).contains("pageerror", "console-error", "failed:r1:PUT:product-api:reason=net::ERR_TIMED_OUT",
                "2ms:submit", "3ms:invalid", "4ms:inline-validation").doesNotContain("SECRET", "private@example.com", "token=");
        for (int i = 0; i < 1000; i++) errors.accept("SECRET");
        assertThat(timeline.summary()).contains("timelineDropped=").hasSizeLessThan(12_000);
        assertThat(timeline.summary().split("pageerror", -1).length).isLessThanOrEqualTo(65);
        assertThat(YagaPublicationTimeline.failure("net::ERR_FAILED SECRET")).isEqualTo("REDACTED_OR_UNAVAILABLE");
        timeline.close();
        verify(page).offPageError(errors);
        verify(page).offConsoleMessage(consoles.getValue());
        verify(page).offRequest(any()); verify(page).offResponse(any());
        verify(page).offRequestFinished(any()); verify(page).offRequestFailed(any());
    }

    @SuppressWarnings("unchecked")
    private Consumer<String> listener(Page page, String ignored) {
        ArgumentCaptor<Consumer<String>> capture = ArgumentCaptor.forClass(Consumer.class);
        verify(page).onPageError(capture.capture());
        return capture.getValue();
    }

    @Test void cleanupContinuesAfterAnIndividualRemovalFails() {
        Page page = mock(Page.class);
        YagaPublicationTimeline timeline = new YagaPublicationTimeline(page);
        timeline.start();
        doThrow(new RuntimeException("closed")).when(page).offRequest(any());
        timeline.close();
        verify(page).offResponse(any()); verify(page).offPageError(any()); verify(page).offConsoleMessage(any());
    }
}
