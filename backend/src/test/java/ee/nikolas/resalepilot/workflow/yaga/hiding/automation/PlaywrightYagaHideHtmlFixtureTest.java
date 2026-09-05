package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightYagaHideHtmlFixtureTest {

    @Test
    void exactPeidaButtonIsReadyOnOldPublicProductPage() {
        withYagaPage("""
                <main>
                  <link rel="canonical"
                        href="https://www.yaga.ee/nik-ar/toode/ip7p454fe6o" />
                  <button type="button">Muuda toodet</button>
                  <button type="button">Märgi müüduks</button>
                  <button type="button">Peida</button>
                  <button type="button">Kustuta</button>
                  <span>ip7p454fe6o</span>
                </main>
                """, inspection -> {
            assertThat(inspection.readyForConfirmation()).isTrue();
            assertThat(inspection.candidateCount()).isEqualTo(1);
            assertThat(inspection.visibleCandidateCount()).isEqualTo(1);
            assertThat(inspection.enabledCandidateCount()).isEqualTo(1);
            assertThat(inspection.controlText()).isEqualTo("Peida");
            assertThat(inspection.tagName()).isEqualTo("button");
            assertThat(inspection.typeAttribute()).isEqualTo("button");
        });
    }

    @Test
    void duplicatePeidaButtonsAreNotReady() {
        withYagaPage("""
                <main>
                  <a href="https://www.yaga.ee/nik-ar/toode/ip7p454fe6o">
                    https://www.yaga.ee/nik-ar/toode/ip7p454fe6o
                  </a>
                  <button type="button">Muuda toodet</button>
                  <button type="button">Peida</button>
                  <button type="button">Peida</button>
                  <span>ip7p454fe6o</span>
                </main>
                """, inspection -> {
            assertThat(inspection.readyForConfirmation()).isFalse();
            assertThat(inspection.candidateCount()).isEqualTo(2);
        });
    }

    @Test
    void disabledPeidaButtonIsNotReady() {
        withYagaPage("""
                <main>
                  <a href="https://www.yaga.ee/nik-ar/toode/ip7p454fe6o">
                    https://www.yaga.ee/nik-ar/toode/ip7p454fe6o
                  </a>
                  <button type="button">Muuda toodet</button>
                  <button type="button" disabled>Peida</button>
                  <span>ip7p454fe6o</span>
                </main>
                """, inspection -> {
            assertThat(inspection.readyForConfirmation()).isFalse();
            assertThat(inspection.candidateCount()).isEqualTo(1);
            assertThat(inspection.enabledCandidateCount()).isZero();
        });
    }

    @Test
    void missingPeidaButtonIsNotReady() {
        withYagaPage("""
                <main>
                  <a href="https://www.yaga.ee/nik-ar/toode/ip7p454fe6o">
                    https://www.yaga.ee/nik-ar/toode/ip7p454fe6o
                  </a>
                  <button type="button">Muuda toodet</button>
                  <button type="button">Märgi müüduks</button>
                  <button type="button">Kustuta</button>
                  <span>ip7p454fe6o</span>
                </main>
                """, inspection -> {
            assertThat(inspection.readyForConfirmation()).isFalse();
            assertThat(inspection.candidateCount()).isZero();
        });
    }

    @Test
    void destructiveAndSoldButtonsAreIgnored() {
        withYagaPage("""
                <main>
                  <a href="https://www.yaga.ee/nik-ar/toode/ip7p454fe6o">
                    https://www.yaga.ee/nik-ar/toode/ip7p454fe6o
                  </a>
                  <button type="button">Muuda toodet</button>
                  <button type="button">Märgi müüduks</button>
                  <button type="button">Kustuta</button>
                  <button type="button">Peida toode</button>
                  <span>ip7p454fe6o</span>
                </main>
                """, inspection -> {
            assertThat(inspection.readyForConfirmation()).isFalse();
            assertThat(inspection.candidateCount()).isZero();
        });
    }

    @Test
    void contextOptionsContainResolvedStorageStatePath()
            throws Exception {

        Path authStatePath =
                Path.of("playwright/.auth/yaga-state.json")
                        .toAbsolutePath()
                        .normalize();

        Object options = automation().contextOptions(authStatePath);

        assertThat(containsFieldValue(options, authStatePath))
                .isTrue();
    }

    @Test
    void missingAuthStateFileFailsBeforeBrowserNavigation() {
        YagaHidingProperties properties =
                new YagaHidingProperties();
        properties.setAuthStatePath(
                "definitely-missing-yaga-state.json"
        );

        assertThatThrownBy(() ->
                new PlaywrightYagaHidingBrowserAutomation(properties)
                        .prepareSession(draft())
        )
                .isInstanceOf(YagaHidingAuthException.class)
                .hasMessage("Yaga auth state file is missing")
                .satisfies(exception -> {
                    YagaHidingAuthException typed =
                            (YagaHidingAuthException) exception;
                    assertThat(typed.getDetails())
                            .containsEntry(
                                    "authStateFileExists",
                                    "false"
                            );
                });
    }

    private boolean containsFieldValue(
            Object object,
            Object expectedValue
    ) throws IllegalAccessException {
        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(object);
            if (expectedValue.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void withYagaPage(
            String html,
            InspectionConsumer consumer
    ) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions()
                             .setChannel("chrome")
                             .setHeadless(true)
             );
             Page page = browser.newPage()) {

            page.route(
                    "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                    route -> route.fulfill(
                            new Route.FulfillOptions()
                                    .setStatus(200)
                                    .setContentType("text/html")
                                    .setBody(html)
                    )
            );
            page.navigate(
                    "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o"
            );

            consumer.accept(automation().inspect(
                    page,
                    draft(),
                    "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                    Path.of("playwright/.auth/yaga-state.json")
            ));
        }
    }

    private PlaywrightYagaHidingBrowserAutomation automation() {
        return new PlaywrightYagaHidingBrowserAutomation(
                new YagaHidingProperties()
        );
    }

    private YagaHidingDraftData draft() {
        return new YagaHidingDraftData(
                1L,
                2L,
                10L,
                "nik-ar",
                "27988552",
                "ip7p454fe6o",
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                "30796018",
                "5u7arpkm6q",
                "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
        );
    }

    private interface InspectionConsumer {

        void accept(YagaHideControlInspection inspection);
    }
}
