package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import ee.nikolas.resalepilot.marketplace.entity.YagaDeliverySettings;
import ee.nikolas.resalepilot.marketplace.entity.YagaPackageSize;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightYagaDeliveryHtmlFixtureTest {
    private final PlaywrightYagaBrowserAutomation automation =
            new PlaywrightYagaBrowserAutomation(new YagaPublishingProperties());

    @Test
    void reproducesNonDefaultCarrierSizesAndAllThreeToggles() {
        withPage(form(), page -> {
            automation.selectDeliverySettings(page, new YagaDeliverySettings(
                    true, size("medium"), true, size("small"), false, null,
                    true, false, false));

            assertThat(page.locator("input[name=omniva][type=checkbox]").isChecked()).isTrue();
            assertThat(page.locator("input[name=omniva][value=medium]").isChecked()).isTrue();
            assertThat(page.locator("input[name=dpd][value=small]").isChecked()).isTrue();
            assertThat(page.locator("input[name=smartpost][type=checkbox]").isChecked()).isFalse();
            assertThat(page.locator("input[name=fromHandToHand]").isChecked()).isTrue();
            assertThat(page.locator("input[name=uponAgreement]").isChecked()).isFalse();
            assertThat(page.locator("input[name=bundling]").isChecked()).isFalse();
        });
    }

    @Test
    void unknownSourceLeavesYagaDefaultsUntouched() {
        withPage(form(), page -> {
            automation.selectDeliverySettings(page, null);
            assertThat(page.locator("input[name=dpd][value=xsmall]").isChecked()).isTrue();
            assertThat(page.locator("input[name=smartpost][type=checkbox]").isChecked()).isTrue();
            assertThat(page.locator("input[name=bundling]").isChecked()).isTrue();
        });
    }

    @Test
    void missingExactPackageSizeFailsBeforePublication() {
        withPage(form(), page -> assertThatThrownBy(() ->
                automation.selectDeliverySettings(page, new YagaDeliverySettings(
                        true, size("huge"), true, size("xsmall"), true, size("small"),
                        false, true, true)))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessageContaining("Yaga package size unavailable: omniva/huge"));
    }

    @Test
    void rejectsStateChangedByYagaAfterInteraction() {
        withPage(form(), page -> assertThatThrownBy(() ->
                automation.selectDeliverySettings(page, new YagaDeliverySettings(
                        false, null, false, null, false, null,
                        true, true, true)))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessageContaining("bundling"));
    }

    private YagaPackageSize size(String code) {
        return new YagaPackageSize(code);
    }

    private void withPage(String html, java.util.function.Consumer<Page> assertion) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(html);
            assertion.accept(page);
        }
    }

    private String form() {
        return """
                <section aria-label="Transpordiviis">
                  <div class="shipping-title"><label><h6>Omniva</h6>
                    <input name="omniva" type="checkbox" checked></label></div>
                  <div class="shipping-package" data-for="omniva">
                    <label>S<input class="shipping-package-radio" name="omniva" type="radio" value="small" checked></label>
                    <label>M<input class="shipping-package-radio" name="omniva" type="radio" value="medium"></label>
                  </div>
                  <div class="shipping-title"><label><h6>DPD</h6>
                    <input name="dpd" type="checkbox" checked></label></div>
                  <div class="shipping-package" data-for="dpd">
                    <label>XS<input class="shipping-package-radio" name="dpd" type="radio" value="xsmall" checked></label>
                    <label>S<input class="shipping-package-radio" name="dpd" type="radio" value="small"></label>
                  </div>
                  <div class="shipping-title"><label><h6>SmartPosti</h6>
                    <input name="smartpost" type="checkbox" checked></label></div>
                  <div class="shipping-package" data-for="smartpost">
                    <label>S<input class="shipping-package-radio" name="smartpost" type="radio" value="small" checked></label>
                  </div>
                  <div class="shipping-title"><label><h6>Ostja tuleb järele</h6>
                    <input name="fromHandToHand" type="checkbox"></label></div>
                  <div class="shipping-title"><label><h6>Kokkuleppel</h6>
                    <input name="uponAgreement" type="checkbox" checked></label></div>
                  <div class="shipping-title"><label><h6>Saatmine ühe pakina</h6>
                    <input name="bundling" type="checkbox" checked></label></div>
                </section>
                <script>
                  function update() {
                    const carrierNames = ['omniva','dpd','smartpost'];
                    for (const name of carrierNames) {
                      document.querySelector('[data-for='+name+']').style.display =
                        document.querySelector('input[type=checkbox][name='+name+']').checked ? '' : 'none';
                    }
                    const hasCarrier = carrierNames.some(name =>
                      document.querySelector('input[type=checkbox][name='+name+']').checked);
                    const bundling = document.querySelector('input[name=bundling]');
                    bundling.disabled = !hasCarrier;
                    if (!hasCarrier) bundling.checked = false;
                  }
                  document.querySelectorAll('input[type=checkbox]').forEach(input =>
                    input.addEventListener('change', update));
                  update();
                </script>
                """;
    }
}
