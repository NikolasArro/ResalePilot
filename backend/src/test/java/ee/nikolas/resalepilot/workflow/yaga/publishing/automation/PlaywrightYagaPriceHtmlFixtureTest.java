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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
    void reportsMissingPriceControlAndOpenListboxBeforeInteraction() {
        withPage(page -> {
            page.setContent("""
                    <div role="listbox"><div role="option">Punane</div></div>
                    <div role="dialog">Visible overlay</div>
                    """);
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation().fillPrice(page, new BigDecimal("17.00"))
            );
            var detail = failure.getDiagnostics().priceFill();
            assertThat(detail.step()).isEqualTo("RESOLVE_CONTROL");
            assertThat(detail.controlFound()).isFalse();
            assertThat(detail.requestedPrice()).isEqualTo("17");
            assertThat(detail.fillAttempted()).isFalse();
            assertThat(detail.visibleListboxCountBefore()).isEqualTo(1);
            assertThat(detail.visibleOverlayCountBefore()).isEqualTo(1);
            assertThat(failure.getDiagnostics().safeErrorCode())
                    .isEqualTo("PRICE_FILL_FAILED");
            assertThat(failure.getDiagnostics().withFailureMetadata(
                    "FILL_PRICE", "failure", "cause", "PRICE_FILL_FAILED"
            ).priceFill()).isEqualTo(detail);
        });
    }

    @Test
    void reportsDisabledPriceControlBeforeFill() {
        withPage(page -> {
            page.setContent("""
                    <div><span>Hind</span>
                      <input type="text" placeholder="0" value="5" disabled>
                    </div>
                    """);
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation().fillPrice(page, new BigDecimal("17.00"))
            );
            var detail = failure.getDiagnostics().priceFill();
            assertThat(detail.step()).isEqualTo("CHECK_EDITABLE");
            assertThat(detail.controlFound()).isTrue();
            assertThat(detail.controlVisible()).isTrue();
            assertThat(detail.controlEnabled()).isFalse();
            assertThat(detail.controlEditable()).isFalse();
            assertThat(detail.valueBeforeFill()).isEqualTo("5");
            assertThat(detail.fillAttempted()).isFalse();
            assertThat(detail.valueObservedAfterFill()).isNull();
        });
    }

    @Test
    void reportsClickTimeoutWithPriceAndPreInteractionOverlayState() {
        withPage(page -> {
            page.setDefaultTimeout(500);
            page.setContent("""
                    <div><span>Hind</span>
                      <input type="text" placeholder="0" value="5">
                    </div>
                    <div role="listbox" style="position:fixed;inset:0;z-index:10;background:white">
                      <div role="option">Punane</div>
                    </div>
                    """);
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation().fillPrice(page, new BigDecimal("17.00"))
            );
            var detail = failure.getDiagnostics().priceFill();
            assertThat(detail.step()).isEqualTo("CLICK");
            assertThat(detail.controlFound()).isTrue();
            assertThat(detail.controlVisible()).isTrue();
            assertThat(detail.controlEnabled()).isTrue();
            assertThat(detail.controlEditable()).isTrue();
            assertThat(detail.valueBeforeFill()).isEqualTo("5");
            assertThat(detail.requestedPrice()).isEqualTo("17");
            assertThat(detail.fillAttempted()).isFalse();
            assertThat(detail.visibleListboxCountBefore()).isEqualTo(1);
            assertThat(detail.valueObservedAfterFill()).isNull();
            assertThat(failure.getDiagnostics().rootCauseClass())
                    .isEqualTo("com.microsoft.playwright.TimeoutError");
        });
    }

    @Test
    void reportsValueObservedAfterAttemptedFillWhenConfirmationFails() {
        withPage(page -> {
            page.setContent("""
                    <div><span>Hind</span>
                      <input type="text" placeholder="0" value="5"
                             onblur="this.value='12'">
                    </div>
                    """);
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation().fillPrice(page, new BigDecimal("17.00"))
            );
            var detail = failure.getDiagnostics().priceFill();
            assertThat(detail.step()).isEqualTo("VERIFY_VALUE");
            assertThat(detail.valueBeforeFill()).isEqualTo("5");
            assertThat(detail.requestedPrice()).isEqualTo("17");
            assertThat(detail.fillAttempted()).isTrue();
            assertThat(detail.valueObservedAfterFill()).isEqualTo("12");
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
