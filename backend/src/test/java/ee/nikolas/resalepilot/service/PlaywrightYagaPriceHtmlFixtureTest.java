package ee.nikolas.resalepilot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import ee.nikolas.resalepilot.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.exception.YagaPublishingFormException;
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
