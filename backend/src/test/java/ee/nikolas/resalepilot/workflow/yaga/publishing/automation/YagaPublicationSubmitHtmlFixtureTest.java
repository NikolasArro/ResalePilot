package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublicationEvidence.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class YagaPublicationSubmitHtmlFixtureTest {
    @Test
    void successfulApiResponsePrecedesDelayedSpaNavigation() {
        withPage(page -> {
            AtomicInteger patches = fixture(page, 200, 200, false);
            var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L)
                    .submit(page.locator("button"), 2_000);
            assertThat(result.evidence().outcome()).isEqualTo(CONFIRMED_SUCCESS);
            assertThat(result.productUrl()).isEqualTo("https://www.yaga.ee/w-a-k-a/toode/new-product");
            assertThat(page.url()).endsWith("/muuk/lisa-toode");
            assertThat(patches.get()).isEqualTo(1);
        });
    }

    @Test
    void apiErrorIsCapturedWithoutAnotherClick() {
        withPage(page -> {
            AtomicInteger patches = fixture(page, 422, 200, false);
            var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L)
                    .submit(page.locator("button"), 2_000);
            assertThat(result.evidence().outcome()).isEqualTo(CONFIRMED_FAILURE);
            assertThat(result.evidence().caseId()).isEqualTo("EE-A-fixture-123");
            assertThat(patches.get()).isEqualTo(1);
        });
    }

    @Test
    void errorAfterPublicationKeepsIdentityButBlocksCompletion() {
        withPage(page -> {
            AtomicInteger patches = fixture(page, 200, 500, false);
            var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L)
                    .submit(page.locator("button"), 2_000);
            assertThat(result.evidence().externalListingId()).isEqualTo(123L);
            assertThat(result.evidence().reason()).isEqualTo("IMAGE_ORDER_SAVE_FAILED");
            assertThat(result.evidence().confirmationAllowed()).isFalse();
            assertThat(patches.get()).isEqualTo(1);
        });
    }

    @Test
    void clientValidationToastIsCapturedBeforeItDisappearsWithoutRequest() {
        withPage(page -> {
            AtomicInteger patches = fixture(page, 200, 200, true);
            var result = new YagaPublicationSubmitObserver(page, "w-a-k-a", 187L)
                    .submit(page.locator("button"), 2_000);
            assertThat(result.evidence().outcome()).isEqualTo(RESULT_UNKNOWN);
            assertThat(result.evidence().caseId()).isEqualTo("EE-A-fixture-123");
            assertThat(patches.get()).isZero();
        });
    }

    private AtomicInteger fixture(Page page, int patchStatus, int imagesStatus, boolean validationError) {
        AtomicInteger patches = new AtomicInteger();
        // Intercept every request, including navigation: no network request reaches Yaga.
        page.route("**/*", route -> {
            String path = java.net.URI.create(route.request().url()).getPath();
            if ("/muuk/lisa-toode".equals(path)) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                        <button onclick="save()">Valmis</button><div id="error"></div>
                        <script>
                        function error() {
                          document.getElementById('error').innerHTML = '<div role="alert" class="notistack-MuiContent-error">Generic error<div class="notistack-case-id">EE-A-fixture-123</div></div>';
                        }
                        async function save() {
                          if (VALIDATION_ERROR) { error(); return; }
                          const r = await fetch('/api/product/123', {method:'PATCH', body:'{}'});
                          if (!r.ok) { error(); return; }
                          await r.json();
                          const images = await fetch('/api/product/123/images', {method:'POST', body:'{}'});
                          if (!images.ok) { error(); return; }
                          setTimeout(() => history.pushState({}, '', '/w-a-k-a/toode/new-product'), 10000);
                        }
                        </script>
                        """.replace("VALIDATION_ERROR", Boolean.toString(validationError))));
            } else if ("/api/product/123".equals(path) || "/api/product/123/images".equals(path)) {
                boolean patch = "PATCH".equals(route.request().method());
                if (patch) patches.incrementAndGet();
                int status = patch ? patchStatus : imagesStatus;
                route.fulfill(new Route.FulfillOptions().setStatus(status).setContentType("application/json")
                        .setBody(status == 200 ? (patch ? """
                                {"status":"success","data":{"id":123,"slug":"new-product","status":"published"}}
                                """ : "{\"status\":\"success\",\"data\":[]}") : """
                                {"status":"error","data":{"caseId":"EE-A-fixture-123","message":"PRIVATE"}}
                                """));
            } else {
                route.abort();
            }
        });
        page.navigate("https://www.yaga.ee/muuk/lisa-toode");
        return patches;
    }

    private void withPage(java.util.function.Consumer<Page> test) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setChannel("chrome").setHeadless(true));
             Page page = browser.newPage()) {
            test.accept(page);
        }
    }
}
