package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class YagaDraftPreparationObserverTest {
    @Test
    void successfulInitializationAndImagesPermitReadinessAndCarryIdentity(CapturedOutput output) {
        withPage(page -> {
            fixture(page, 201, 123, false);
            try (var observer = new YagaDraftPreparationObserver(page, 175L, "w-a-k-a")) {
                page.navigate("https://www.yaga.ee/muuk/lisa-toode");
                var identity = observer.requireReady(3000);
                assertThat(identity.id()).isEqualTo(123);
                assertThat(identity.slug()).isEqualTo("draft-one");
                var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 175L, identity.id())
                        .submit(page.locator("button"), 1000);
                assertThat(result.evidence().externalListingId()).isEqualTo(identity.id());
                assertThat(result.evidence().confirmationAllowed()).isTrue();
            }
        });
        assertThat(output).contains("preparedDraftId=123", "draftId=123", "draftSlug=draft-one")
                .doesNotContain("SECRET");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500})
    void initializationErrorPreventsReadinessAndCapturesSafeCaseId(int status, CapturedOutput output) {
        withPage(page -> {
            fixture(page, status, 123, false);
            try (var observer = new YagaDraftPreparationObserver(page, 175L, "w-a-k-a")) {
                page.navigate("https://www.yaga.ee/muuk/lisa-toode");
                assertThatThrownBy(() -> observer.requireReady(3000))
                        .isInstanceOf(YagaPublishingFormException.class)
                        .hasMessageContaining("DRAFT_INITIALIZATION_REJECTED");
                assertThat(page.evaluate("window.publishClicks")).isEqualTo(0);
            }
        });
        assertThat(output).contains("caseId=EE-A-fixture-init", "httpStatus=" + status)
                .doesNotContain("SECRET");
    }

    @Test
    void preparationErrorToastPreventsReadinessEvenAfterItDisappears() {
        withPage(page -> {
            fixture(page, 201, 123, true);
            try (var observer = new YagaDraftPreparationObserver(page, 175L, "w-a-k-a")) {
                page.navigate("https://www.yaga.ee/muuk/lisa-toode");
                page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate("window.__resalePilotDraftError")));
                page.locator(".notistack-MuiContent-error").evaluate("e => e.remove()");
                assertThatThrownBy(() -> observer.requireReady(3000)).isInstanceOf(YagaPublishingFormException.class);
                assertThatThrownBy(() -> YagaDraftPreparationObserver.requireNoError(page))
                        .isInstanceOf(YagaPublishingFormException.class);
                assertThat(page.evaluate("window.publishClicks")).isEqualTo(0);
            }
        });
    }

    @Test
    void imageRequestForAnotherDraftCannotBecomeReady() {
        withPage(page -> {
            fixture(page, 201, 456, false);
            try (var observer = new YagaDraftPreparationObserver(page, 175L, "w-a-k-a")) {
                page.navigate("https://www.yaga.ee/muuk/lisa-toode");
                assertThatThrownBy(() -> observer.requireReady(3000))
                        .isInstanceOf(YagaPublishingFormException.class).hasMessageContaining("DRAFT_IMAGE_ID_MISMATCH");
            }
        });
    }

    @Test
    void missingInitializationCannotBecomeReady() {
        withPage(page -> {
            page.route("**/*", route -> route.fulfill(new Route.FulfillOptions().setBody("<button>Valmis</button>")));
            try (var observer = new YagaDraftPreparationObserver(page, 175L, "w-a-k-a")) {
                page.navigate("https://www.yaga.ee/muuk/lisa-toode");
                assertThatThrownBy(() -> observer.requireReady(150)).isInstanceOf(YagaPublishingFormException.class);
            }
        });
    }

    private void fixture(Page page, int initStatus, long imageId, boolean toast) {
        page.route("**/*", route -> {
            String path = java.net.URI.create(route.request().url()).getPath();
            if (path.equals("/muuk/lisa-toode")) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                        <button onclick="publish()">Valmis</button>
                        <script>
                        window.publishClicks=0;
                        async function init() {
                          const r=await fetch('/api/product', {method:'POST',body:'{}'});
                          await r.json();
                          if (!r.ok) return;
                          await fetch('/api/product/IMAGE_ID/images',{method:'POST',body:'{}'});
                          if (SHOW_TOAST) document.body.insertAdjacentHTML('beforeend','<div class="notistack-MuiContent-error">Error EE-A-fixture-init</div>');
                        }
                        async function publish() {
                          window.publishClicks++;
                          await fetch('/api/product/123',{method:'PATCH',body:'{}'});
                          await fetch('/api/product/123/images',{method:'POST',body:'{}'});
                        }
                        init();
                        </script>
                        """.replace("IMAGE_ID", Long.toString(imageId)).replace("SHOW_TOAST", Boolean.toString(toast))));
            } else if (path.equals("/api/product")) {
                route.fulfill(new Route.FulfillOptions().setStatus(initStatus).setContentType("application/json")
                        .setBody(initStatus < 300 ? """
                                {"status":"success","data":{"id":123,"slug":"draft-one","status":"draft","token":"SECRET"}}
                                """ : """
                                {"status":"error","data":{"caseId":"EE-A-fixture-init","message":"SECRET"}}
                                """));
            } else if (path.endsWith("/images")) {
                route.fulfill(new Route.FulfillOptions().setContentType("application/json")
                        .setBody("{\"status\":\"success\",\"data\":[]}"));
            } else if (path.equals("/api/product/123")) {
                route.fulfill(new Route.FulfillOptions().setContentType("application/json")
                        .setBody("{\"status\":\"success\",\"data\":{\"id\":123,\"slug\":\"draft-one\",\"status\":\"published\"}}"));
            } else route.abort();
        });
    }

    private void withPage(java.util.function.Consumer<Page> test) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
             Page page = browser.newPage()) {
            test.accept(page);
        }
    }
}
