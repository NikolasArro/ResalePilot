package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightYagaPriceHtmlFixtureTest {

    @Test
    void fillsPriceWhenHindLabelIsOnlyTextInCommonContainer() {
        withPage(page -> {
            page.setContent("""
                    <main>
                      <div class="field">
                        <span>Hind</span>
                        <input type="text" placeholder="0" />
                      </div>
                    </main>
                    """);

            automation().fillPrice(page, new BigDecimal("17.00"));

            assertThat(page.locator("input").inputValue())
                    .isEqualTo("17");
        });
    }

    @Test
    void fillsPriceViaUniqueVisiblePlaceholderFallback() {
        withPage(page -> {
            page.setContent("""
                    <main>
                      <input type="text" placeholder="0" />
                    </main>
                    """);

            automation().fillPrice(page, new BigDecimal("17.00"));

            assertThat(page.locator("input").inputValue())
                    .isEqualTo("17");
        });
    }

    @Test
    void rejectsMultipleVisiblePlaceholderFallbackCandidates() {
        withPage(page -> {
            page.setContent("""
                    <main>
                      <input type="text" placeholder="0" />
                      <input type="text" placeholder="0" />
                    </main>
                    """);

            assertThatThrownBy(() ->
                    automation().priceField(page)
            )
                    .isInstanceOf(
                            YagaPublishingFormException.class
                    )
                    .hasMessage(
                            "Yaga price field is not accessible"
                    );
        });
    }

    @Test
    void publishReadinessFindsConfirmedMuiValmisButton() {
        withPage(page -> {
            page.route(
                    "https://www.yaga.ee/muuk/lisa-toode",
                    route -> route.fulfill(
                            new Route.FulfillOptions()
                                    .setStatus(200)
                                    .setContentType("text/html")
                                    .setBody("""
                                            <main>
                                              <button class="MuiButtonBase-root MuiButton-root MuiButton-contained MuiButton-containedPrimary MuiButton-sizeLarge MuiButton-containedSizeLarge MuiButton-fullWidth MuiButton-root MuiButton-contained MuiButton-containedPrimary MuiButton-sizeLarge MuiButton-containedSizeLarge MuiButton-fullWidth css-yerniy"
                                                      tabindex="0"
                                                      type="button">
                                                  Valmis
                                                  <span class="MuiTouchRipple-root css-w0pj6f"></span>
                                              </button>
                                            </main>
                                            """)
                    )
            );
            page.navigate("https://www.yaga.ee/muuk/lisa-toode");

            YagaPublishControlInspection inspection =
                    automation().inspectPublishControl(page, true)
                            .inspection();

            assertThat(inspection.readyForConfirmation()).isTrue();
            assertThat(inspection.candidateCount()).isEqualTo(1);
            assertThat(inspection.buttonText()).isEqualTo("Valmis");
            assertThat(inspection.tagName()).isEqualTo("button");
            assertThat(inspection.typeAttribute()).isEqualTo("button");
        });
    }

    private PlaywrightYagaBrowserAutomation automation() {
        return new PlaywrightYagaBrowserAutomation(
                new YagaPublishingProperties()
        );
    }

    private void withPage(PageConsumer consumer) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions()
                             .setChannel("chrome")
                             .setHeadless(true)
             );
             Page page = browser.newPage()) {

            consumer.accept(page);
        }
    }

    private interface PageConsumer {

        void accept(Page page);
    }
}
