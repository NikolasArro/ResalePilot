package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaConditionSelection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingOperationStage;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaConditionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightYagaBrowserAutomationTest {

    @Test
    void rejectsMismatchedAccountBeforeOpeningPublicationSession() {
        YagaAccount account = account(2L, "other-shop");

        assertThatThrownBy(() -> automation().prepareSession(
                draft(),
                List.of(),
                account
        ))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Yaga account does not match publication draft");
    }

    @Test
    void missingAuthStateFailsBeforeOpeningPublicationSessionWithDiagnostics() {
        YagaAccount account = account(1L, "shop");
        account.setAuthStatePath(
                Path.of("target", "missing-auth-state.json").toString()
        );

        Throwable exception = catchThrowable(() -> automation().prepareSession(
                draft(),
                List.of(),
                account
        ));

        assertThat(exception)
                .isInstanceOf(YagaPublishingAuthException.class)
                .hasMessage("Yaga auth state file is missing");
        YagaPublishingAuthException typed =
                (YagaPublishingAuthException) exception;
        assertThat(typed.getDiagnostics()).isNotNull();
        assertThat(typed.getDiagnostics().operationStage())
                .isEqualTo(YagaPublishingOperationStage.RESOLVE_AUTH_STATE
                        .name());
        assertThat(typed.getDiagnostics().safeErrorCode())
                .isEqualTo("AUTH_STATE_FILE_MISSING");
    }

    @Test
    void treatsFormAsAccessibleWhenDelayedRequiredLocatorsAppear() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);

        mockDiagnostics(
                page,
                "https://www.yaga.ee/muuk/lisa-toode",
                "Lisa toode",
                1,
                1,
                0
        );

        automation.ensureFormAccessible(page);

        verify(page).waitForSelector(
                eq("textarea[placeholder=\"Kirjelda toodet\"]"),
                any(Page.WaitForSelectorOptions.class)
        );
    }

    @Test
    void redirectsAwayFromCreateFormAreAuthFailures() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);

        mockDiagnostics(
                page,
                "https://www.yaga.ee/login",
                "Login",
                0,
                0,
                1
        );

        Throwable exception = catchThrowable(
                () -> automation.ensureFormAccessible(page)
        );

        assertThat(exception)
                .isInstanceOf(YagaPublishingAuthException.class)
                .hasMessageContaining("not authorized");
        YagaPublishingAuthException typed =
                (YagaPublishingAuthException) exception;
        assertThat(typed.getDiagnostics().operationStage())
                .isEqualTo(YagaPublishingOperationStage.OPEN_FORM.name());
        assertThat(typed.getDiagnostics().safeErrorCode())
                .isEqualTo("AUTH_SESSION_INVALID");

        verify(page, never()).waitForSelector(any(), any());
    }

    @Test
    void missingFormOnCreateUrlIsFormFailure() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);

        mockDiagnostics(
                page,
                "https://www.yaga.ee/muuk/lisa-toode",
                "Lisa toode",
                0,
                1,
                0
        );
        when(page.waitForSelector(
                eq("textarea[placeholder=\"Kirjelda toodet\"]"),
                any(Page.WaitForSelectorOptions.class)
        ))
                .thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> automation.ensureFormAccessible(page))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Yaga listing form is not accessible");
    }

    @Test
    void findsPriceInputInsideLocalHindContainer() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator labels = mock(Locator.class);
        Locator label = mock(Locator.class);
        Locator container = mock(Locator.class);
        Locator inputs = mock(Locator.class);
        Locator input = mock(Locator.class);

        when(page.getByText(any(Pattern.class))).thenReturn(labels);
        when(labels.count()).thenReturn(1);
        when(labels.nth(0)).thenReturn(label);
        when(label.locator(
                "xpath=ancestor::*[.//input[@type='text']][1]"
        ))
                .thenReturn(container);
        when(container.locator("input[type='text']"))
                .thenReturn(inputs);
        when(inputs.count()).thenReturn(1);
        when(inputs.nth(0)).thenReturn(input);
        when(input.isVisible()).thenReturn(true);

        assertThat(automation.priceField(page)).isSameAs(input);
    }

    @Test
    void usesUniqueVisiblePlaceholderFallbackForPrice() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator labels = mock(Locator.class);
        Locator placeholders = mock(Locator.class);
        Locator input = mock(Locator.class);

        when(page.getByText(any(Pattern.class))).thenReturn(labels);
        when(labels.count()).thenReturn(0);
        when(page.locator("input[type='text'][placeholder='0']"))
                .thenReturn(placeholders);
        when(placeholders.count()).thenReturn(1);
        when(placeholders.nth(0)).thenReturn(input);
        when(input.isVisible()).thenReturn(true);

        assertThat(automation.priceField(page)).isSameAs(input);
    }

    @Test
    void rejectsMultipleVisiblePlaceholderFallbackCandidates() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator labels = mock(Locator.class);
        Locator placeholders = mock(Locator.class);
        Locator first = mock(Locator.class);
        Locator second = mock(Locator.class);

        when(page.getByText(any(Pattern.class))).thenReturn(labels);
        when(labels.count()).thenReturn(0);
        when(page.locator("input[type='text'][placeholder='0']"))
                .thenReturn(placeholders);
        when(placeholders.count()).thenReturn(2);
        when(placeholders.nth(0)).thenReturn(first);
        when(placeholders.nth(1)).thenReturn(second);
        when(first.isVisible()).thenReturn(true);
        when(second.isVisible()).thenReturn(true);
        when(first.evaluate(any())).thenReturn(
                "<input type=\"text\" placeholder=\"0\">"
        );
        when(second.evaluate(any())).thenReturn(
                "<input type=\"text\" placeholder=\"0\">"
        );

        assertThatThrownBy(() -> automation.priceField(page))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Yaga price field is not accessible");
    }

    @Test
    void fillsPriceAndConfirmsValueAfterBlur() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator labels = mock(Locator.class);
        Locator placeholders = mock(Locator.class);
        Locator input = mock(Locator.class);

        when(page.getByText(any(Pattern.class))).thenReturn(labels);
        when(labels.count()).thenReturn(0);
        when(page.locator("input[type='text'][placeholder='0']"))
                .thenReturn(placeholders);
        when(placeholders.count()).thenReturn(1);
        when(placeholders.nth(0)).thenReturn(input);
        when(input.isVisible()).thenReturn(true);
        when(input.isEditable()).thenReturn(true);
        when(input.inputValue()).thenReturn("17 \u20ac");

        automation.fillPrice(page, new BigDecimal("17.00"));

        verify(input).scrollIntoViewIfNeeded();
        verify(input).waitFor(any(Locator.WaitForOptions.class));
        verify(input).click();
        verify(input).fill("17");
        verify(input).press("Tab");
    }

    @Test
    void selectsVisibleExactConditionAndIgnoresHiddenDuplicate() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            page.setContent(conditionSelectHtml(false));

            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uuev\u00e4\u00e4rne")
            );

            assertThat(page.locator("#condition-trigger").textContent())
                    .matches(text ->
                            "Uuev\u00e4\u00e4rne".equals(text.trim())
                    );
            assertThat(page.locator("#visible-option").getAttribute("data-clicked"))
                    .isEqualTo("true");
            assertThat(page.locator("#global-option").getAttribute("data-clicked"))
                    .isNull();
            assertThat(page.locator("#hidden-option").getAttribute("data-clicked"))
                    .isNull();

            browser.close();
        }
    }

    @Test
    void selectsUuevaearneForVeryGoodImportedCondition() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(conditionSelectHtml(false));

            automation().selectCondition(
                    page,
                    YagaConditionMapper.toYaga(ProductCondition.VERY_GOOD)
            );

            assertThat(page.locator("#condition-trigger").textContent())
                    .matches(text ->
                            "Uuev\u00e4\u00e4rne".equals(text.trim())
                    );
            assertThat(page.locator("#visible-option")
                    .getAttribute("data-clicked")).isEqualTo("true");
            assertThat(page.locator("#hea-option")
                    .getAttribute("data-clicked")).isNull();
        }
    }

    @Test
    void rejectsMoreThanOneVisibleConditionListbox() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(conditionSelectHtml(true));

            Throwable thrown = catchThrowable(() -> automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uuev\u00e4\u00e4rne")
            ));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("not uniquely available");
            assertThat(((YagaPublishingFormException) thrown)
                    .getDiagnostics().visibleListboxCount()).isEqualTo(2);
        }
    }

    @Test
    void selectsEnabledConditionAndIgnoresDisabledDuplicate() {
        withConditionPage("""
                <li id="enabled" role="option"><span>Uus</span></li>
                <li id="disabled" role="option" aria-disabled="true"><span>Uus</span></li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uus")
            );

            assertThat(page.locator("#enabled").getAttribute("data-clicked"))
                    .isEqualTo("true");
            assertThat(page.locator("#disabled").getAttribute("data-clicked"))
                    .isNull();
        });
    }

    @Test
    void selectsVisibleConditionAndIgnoresHiddenDuplicateInListbox() {
        withConditionPage("""
                <li id="visible-average" role="option">Keskmine</li>
                <li id="hidden-average" role="option" style="display:none">Keskmine</li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            assertThat(page.locator("#visible-average")
                    .getAttribute("data-clicked")).isEqualTo("true");
            assertThat(page.locator("#hidden-average")
                    .getAttribute("data-clicked")).isNull();
        });
    }

    @Test
    void selectsRoleOptionByExactLabelWithoutComparingDescription() {
        withConditionPage("""
                <li id="average" role="option">
                  <span class="condition-line">Keskmine</span>
                  <span class="condition-line">Märgatavate kasutusjälgedega, mis on välja toodud kirjelduses</span>
                </li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            assertThat(page.locator("#average").getAttribute("data-clicked"))
                    .isEqualTo("true");
            assertThat(page.locator("#condition-trigger span").count())
                    .isEqualTo(2);
        });
    }

    @Test
    void selectsConditionWhenLabelAndDescriptionArePlainTextNodes() {
        withConditionPage("""
                <li id="plain-text" class="plain-lines" role="option">Keskmine
                Märgatavate kasutusjälgedega</li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            assertThat(page.locator("#plain-text")
                    .getAttribute("data-clicked")).isEqualTo("true");
        });
    }

    @Test
    void selectsRolelessDirectListboxItemByExactLabel() {
        withConditionPage("""
                <li id="roleless-average">
                  <span class="condition-line">Keskmine</span>
                  <span class="condition-line">Märgatavate kasutusjälgedega</span>
                </li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            assertThat(page.locator("#roleless-average")
                    .getAttribute("data-clicked")).isEqualTo("true");
        });
    }

    @Test
    void rejectsTwoVisibleEnabledExactConditions() {
        withConditionPage("""
                <li role="option"><span class="condition-line">Keskmine</span><span class="condition-line">Kirjeldus üks</span></li>
                <li role="option"><span class="condition-line">Keskmine</span><span class="condition-line">Kirjeldus kaks</span></li>
                """, page -> {
            Throwable thrown = catchThrowable(() -> automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            ));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("not uniquely available");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().visibleListboxCount())
                    .isEqualTo(1);
            assertThat(exception.getDiagnostics().exactCandidateCount())
                    .isEqualTo(2);
            assertThat(exception.getDiagnostics().visibleExactCandidateCount())
                    .isEqualTo(2);
            assertThat(exception.getDiagnostics()
                    .enabledVisibleExactCandidateCount()).isEqualTo(2);
            assertThat(exception.getDiagnostics().candidateRoles())
                    .containsOnly("option");
            assertThat(exception.getDiagnostics().exactLabelNodeCount())
                    .isEqualTo(2);
            assertThat(exception.getDiagnostics()
                    .resolvedSemanticContainerCount()).isEqualTo(2);
            assertThat(exception.getDiagnostics()
                    .enabledResolvedContainerCount()).isEqualTo(2);
            assertThat(exception.getDiagnostics().semanticContainerCount())
                    .isEqualTo(2);
            assertThat(exception.getDiagnostics()
                    .enabledVisibleExactOptionLabelMatchCount()).isEqualTo(2);
            assertThat(exception.getDiagnostics().optionLabelExamples())
                    .containsExactly("Keskmine");
        });
    }

    @Test
    void rejectsWhenNoExactConditionExists() {
        withConditionPage("""
                <li role="option">Uuev&auml;&auml;rne</li>
                """, page -> {
            Throwable thrown = catchThrowable(() -> automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uus")
            ));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("not uniquely available");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().exactCandidateCount())
                    .isZero();
            assertThat(exception.getDiagnostics()
                    .enabledVisibleExactCandidateCount()).isZero();
        });
    }

    @Test
    void nestedDuplicateNodesRepresentOneSemanticConditionOption() {
        withConditionPage("""
                <li id="semantic-option" role="option">
                  <span><span>Uus</span></span>
                </li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uus")
            );

            assertThat(page.locator("#semantic-option")
                    .getAttribute("data-clicked")).isEqualTo("true");
        });
    }

    @Test
    void exactConditionLabelsDoNotMixUusAndUuevaearne() {
        withConditionPage("""
                <li id="uus" role="option"><span class="condition-line">Uus</span><span class="condition-line">Uus toode</span></li>
                <li id="uuevaearne" role="option"><span class="condition-line">Uuev&auml;&auml;rne</span><span class="condition-line">Väheste jälgedega</span></li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Uus")
            );

            assertThat(page.locator("#uus").getAttribute("data-clicked"))
                    .isEqualTo("true");
            assertThat(page.locator("#uuevaearne")
                    .getAttribute("data-clicked")).isNull();
        });
    }

    @Test
    void waitsForConditionTriggerToRenderSelectedLabelAfterClick() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent("""
                    <style>.line { display: block; }</style>
                    <button id="trigger" type="button"
                            onclick="document.querySelector('#options').style.display='block'">
                      Vali seisukord
                    </button>
                    <ul id="options" role="listbox" style="display:none">
                      <li id="average" role="option" onclick="
                        document.querySelector('#options').style.display='none';
                        setTimeout(() => {
                          document.querySelector('#trigger').innerHTML =
                            '<span class=&quot;line&quot;>Keskmine</span>' +
                            '<span class=&quot;line&quot;>Märgatavate kasutusjälgedega</span>';
                        }, 150)">
                        <span class="line">Keskmine</span>
                        <span class="line">Märgatavate kasutusjälgedega</span>
                      </li>
                    </ul>
                    """);

            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            assertThat(page.locator("#options").isVisible()).isFalse();
            assertThat(page.locator("#trigger span").count()).isEqualTo(2);
            assertThat(automation().readSelectedConditionLabel(
                    page,
                    "Keskmine"
            )).isEqualTo("Keskmine");
        }
    }

    @Test
    void nextFormStepFailureUsesFillPriceStage() {
        withConditionPage("""
                <li role="option">Keskmine</li>
                """, page -> {
            automation().selectCondition(
                    page,
                    new YagaConditionSelection("Keskmine")
            );

            Throwable thrown = catchThrowable(() -> automation().fillPrice(
                    page,
                    new BigDecimal("17.00")
            ));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessage("Yaga price field is not accessible");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().operationStage())
                    .isEqualTo("FILL_PRICE");
            assertThat(exception.getDiagnostics().safeErrorCode())
                    .isEqualTo("PRICE_FILL_FAILED");
        });
    }

    @Test
    void conditionMismatchBlocksPreparedFormValidation() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        mockConfirmedForm(page);
        mockVisibleConditionLabel(page, "Hea");

        YagaListingDraftData draft = new YagaListingDraftData(
                10L,
                1L,
                "shop",
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                ProductCondition.NEW_WITHOUT_TAGS,
                List.of("Raamatud"),
                List.of(new YagaListingDraftData.Image(
                        "drive-1",
                        "image.jpg",
                        0,
                        true
                ))
        );

        PlaywrightYagaBrowserAutomation.PlaywrightPreparedBrowserSession session =
                new PlaywrightYagaBrowserAutomation
                        .PlaywrightPreparedBrowserSession(
                        UUID.randomUUID(),
                        draft,
                        formResult(),
                        mock(Playwright.class),
                        mock(Browser.class),
                        mock(BrowserContext.class),
                        page
                );

        Throwable thrown = catchThrowable(
                () -> automation.verifyPreparedForm(session)
        );
        assertThat(thrown)
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Yaga condition selection was not confirmed");
        YagaPublishingFormException exception =
                (YagaPublishingFormException) thrown;
        assertThat(exception.getDiagnostics().operationStage())
                .isEqualTo("INSPECT_CONDITION");
        assertThat(exception.getDiagnostics().safeErrorCode())
                .isEqualTo("CONDITION_SELECTION_NOT_CONFIRMED");
        assertThat(exception.getDiagnostics().expectedConditionLabel())
                .isEqualTo("Uuev\u00e4\u00e4rne");
        assertThat(exception.getDiagnostics().actualConditionLabel())
                .isEqualTo("Hea");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(session);

        assertThat(inspection.readyForConfirmation()).isFalse();
    }

    @Test
    void readinessInspectionHandlesMissingConditionControlWithoutNpe() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(localPreparedFormHtml("""
                    <div>Vali seisukord</div>
                    """));

            Throwable thrown = catchThrowable(() -> automation()
                    .verifyPreparedForm(preparedSession(page, draft())));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessage("Yaga condition selection was not confirmed");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().operationStage())
                    .isEqualTo("INSPECT_CONDITION");
            assertThat(exception.getDiagnostics().safeErrorCode())
                    .isEqualTo("CONDITION_SELECTION_NOT_CONFIRMED");
            assertThat(exception.getDiagnostics().actualConditionLabel())
                    .isNull();
        }
    }

    @Test
    void readinessInspectionHandlesBlankVisibleControlsWithoutNpe() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(localPreparedFormHtml("""
                    <button id="blank-condition" type="button"></button>
                    <div role="combobox">   </div>
                    """));

            Throwable thrown = catchThrowable(() -> automation()
                    .verifyPreparedForm(preparedSession(page, draft())));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessage("Yaga condition selection was not confirmed");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().operationStage())
                    .isEqualTo("INSPECT_CONDITION");
            assertThat(exception.getDiagnostics().safeErrorCode())
                    .isEqualTo("CONDITION_SELECTION_NOT_CONFIRMED");
            assertThat(exception.getDiagnostics().actualConditionLabel())
                    .isNull();
        }
    }

    @Test
    void conditionResolutionHandlesBlankOptionLabelsWithoutNpe() {
        withConditionPage("""
                <li role="option"></li>
                <li role="option">   </li>
                """, page -> {
            Throwable thrown = catchThrowable(() -> automation()
                    .selectCondition(
                            page,
                            new YagaConditionSelection("Keskmine")
                    ));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("not uniquely available");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().operationStage())
                    .isEqualTo("RESOLVE_CONDITION_OPTION");
            assertThat(exception.getDiagnostics()
                    .enabledVisibleExactOptionLabelMatchCount()).isZero();
        });
    }

    @Test
    void readinessInspectionHandlesIncompleteDomWithoutNpe() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent("""
                    <textarea placeholder="Kirjelda toodet">Description</textarea>
                    <button>Hea</button>
                    <div>Raamatud</div>
                    """);

            Throwable thrown = catchThrowable(() -> automation()
                    .verifyPreparedForm(preparedSession(page, draft())));

            assertThat(thrown)
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessage("Yaga price field is not accessible");
            YagaPublishingFormException exception =
                    (YagaPublishingFormException) thrown;
            assertThat(exception.getDiagnostics().operationStage())
                    .isEqualTo("INSPECT_FORM_VALIDITY");
            assertThat(exception.getDiagnostics().safeErrorCode())
                    .isEqualTo("PRICE_VALIDITY_INSPECTION_FAILED");
        }
    }

    @Test
    void readinessInspectionReturnsConfirmedFormForCompleteLocalDom() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(localPreparedFormHtml("""
                    <button id="condition" type="button">
                      <span style="display:block">Hea</span>
                      <span style="display:block">Võivad esineda vähesed kasutusjäljed</span>
                    </button>
                    """));

            YagaFormFillResult result = automation()
                    .verifyPreparedForm(preparedSession(page, draft()));

            assertThat(result.descriptionFilled()).isTrue();
            assertThat(result.categoryPath()).containsExactly("Raamatud");
            assertThat(result.conditionLabel()).isEqualTo("Hea");
            assertThat(result.price()).isEqualByComparingTo("17");
        }
    }

    @Test
    void publishButtonInspectionHandlesMissingOptionalButtonText() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent("""
                    <button type="button" aria-label="Valmis"></button>
                    """);

            YagaPublishControlInspection inspection =
                    automation().inspectPublishControl(page, true)
                            .inspection();

            assertThat(inspection.readyForConfirmation()).isFalse();
            assertThat(inspection.candidateCount()).isZero();
            assertThat(inspection.buttonText()).isNull();
        }
    }

    @Test
    void publishButtonInspectionHandlesMissingTypeAttribute() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                true,
                "button",
                null
        );

        mockPublishRoleLocator(page, buttons, button);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isTrue();
        assertThat(inspection.typeAttribute()).isNull();
    }

    @Test
    void normalizesFormattedPriceValues() {
        assertThat(PlaywrightYagaBrowserAutomation
                .normalizePrice("17"))
                .isEqualByComparingTo("17");
        assertThat(PlaywrightYagaBrowserAutomation
                .normalizePrice("17.0"))
                .isEqualByComparingTo("17");
        assertThat(PlaywrightYagaBrowserAutomation
                .normalizePrice("17,00"))
                .isEqualByComparingTo("17");
        assertThat(PlaywrightYagaBrowserAutomation
                .normalizePrice("17 \u20ac"))
                .isEqualByComparingTo("17");
    }

    @Test
    void capturesFailureScreenshotWithDiagnosticFileName() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        doAnswer(invocation -> {
            Page.ScreenshotOptions options =
                    invocation.getArgument(0);
            Files.createFile((Path) options.path);
            return null;
        })
                .when(page)
                .screenshot(any(Page.ScreenshotOptions.class));

        Path screenshotPath =
                automation.takeFailureScreenshot(page);

        ArgumentCaptor<Page.ScreenshotOptions> optionsCaptor =
                ArgumentCaptor.captor();
        verify(page).screenshot(optionsCaptor.capture());

        assertThat(screenshotPath.getFileName().toString())
                .startsWith("yaga-prepare-failure-")
                .endsWith(".png");
        assertThat(optionsCaptor.getValue())
                .extracting("path")
                .isEqualTo(screenshotPath);
    }

    @Test
    void inspectFindsOneVisibleEnabledValmisButton() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                true,
                "button",
                ""
        );

        mockPublishRoleLocator(page, buttons, button);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isTrue();
        assertThat(inspection.candidateCount()).isEqualTo(1);
        assertThat(inspection.visibleCandidateCount()).isEqualTo(1);
        assertThat(inspection.enabledCandidateCount()).isEqualTo(1);
        assertThat(inspection.buttonText()).isEqualTo("Valmis");
        assertThat(inspection.tagName()).isEqualTo("button");
        assertThat(inspection.typeAttribute()).isEmpty();
        verify(button, never()).click();
    }

    @Test
    void valmisButtonTypeButtonIsReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                true,
                "button",
                "button"
        );

        mockPublishRoleLocator(page, buttons, button);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isTrue();
        assertThat(inspection.typeAttribute()).isEqualTo("button");
    }

    @Test
    void fallbackFindsMuiButtonWhenRoleLocatorReturnsNoCandidates() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator roleButtons = mock(Locator.class);
        Locator fallbackBase = mock(Locator.class);
        Locator fallbackButtons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                true,
                "button",
                "button"
        );

        when(page.getByRole(
                eq(AriaRole.BUTTON),
                any(Page.GetByRoleOptions.class)
        ))
                .thenReturn(roleButtons);
        when(roleButtons.all()).thenReturn(List.of());
        when(page.locator("button[type='button']"))
                .thenReturn(fallbackBase);
        when(fallbackBase.filter(any(Locator.FilterOptions.class)))
                .thenReturn(fallbackButtons);
        when(fallbackButtons.all()).thenReturn(List.of(button));
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isTrue();
        assertThat(inspection.buttonText()).isEqualTo("Valmis");
        assertThat(inspection.typeAttribute()).isEqualTo("button");
        verify(button, never()).click();
    }

    @Test
    void valmisButtonTypeSubmitIsReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                true,
                "button",
                "submit"
        );

        mockPublishRoleLocator(page, buttons, button);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isTrue();
        assertThat(inspection.typeAttribute()).isEqualTo("submit");
    }

    @Test
    void disabledPublishButtonIsNotReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator button = publishButton(
                "Valmis",
                true,
                false,
                "button",
                "submit"
        );

        mockPublishRoleLocator(page, buttons, button);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isFalse();
        assertThat(inspection.enabledCandidateCount()).isZero();
        verify(button, never()).click();
    }

    @Test
    void twoValmisCandidatesAreNotReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);
        Locator first = publishButton(
                "Valmis",
                true,
                true,
                "button",
                "submit"
        );
        Locator second = publishButton(
                "Valmis",
                true,
                true,
                "button",
                "submit"
        );

        mockPublishRoleLocator(page, buttons, first, second);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isFalse();
        assertThat(inspection.candidateCount()).isEqualTo(2);
        verify(first, never()).click();
        verify(second, never()).click();
    }

    @Test
    void missingPublishButtonIsNotReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);

        mockPublishRoleLocator(page, buttons);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isFalse();
        assertThat(inspection.candidateCount()).isZero();
    }

    @Test
    void lisaToodeWithoutValmisIsNotReady() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        Locator buttons = mock(Locator.class);

        mockPublishRoleLocator(page, buttons);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");

        YagaPublishControlInspection inspection =
                automation.inspectPublishControl(page, true)
                        .inspection();

        assertThat(inspection.readyForConfirmation()).isFalse();
        assertThat(inspection.candidateCount()).isZero();
    }

    @Test
    void publishResolvesButtonAgainAfterInspection() {
        PlaywrightYagaBrowserAutomation automation =
                automation();
        Page page = mock(Page.class);
        mockConfirmedForm(page);

        Locator buttons = mock(Locator.class);
        Locator firstResolution =
                publishButton(
                        "Valmis",
                        true,
                        true,
                        "button",
                        "submit"
                );
        Locator secondResolution =
                publishButton(
                        "Valmis",
                        true,
                        true,
                        "button",
                        "submit"
                );

        when(page.getByRole(
                eq(AriaRole.BUTTON),
                any(Page.GetByRoleOptions.class)
        ))
                .thenReturn(buttons, buttons);
        when(buttons.all())
                .thenReturn(
                        List.of(firstResolution),
                        List.of(secondResolution)
                );
        when(page.url())
                .thenReturn(
                        "https://www.yaga.ee/muuk/lisa-toode",
                        "https://www.yaga.ee/muuk/lisa-toode",
                        "https://www.yaga.ee/muuk/lisa-toode",
                        "https://www.yaga.ee/muuk/lisa-toode",
                        "https://www.yaga.ee/shop/toode/book"
                );

        YagaListingDraftData draft = draft();
        PlaywrightYagaBrowserAutomation.PlaywrightPreparedBrowserSession session =
                new PlaywrightYagaBrowserAutomation
                        .PlaywrightPreparedBrowserSession(
                        UUID.randomUUID(),
                        draft,
                        formResult(),
                        mock(Playwright.class),
                        mock(Browser.class),
                        mock(BrowserContext.class),
                        page
                );

        automation.inspectPublishControl(page, true);
        YagaPublishResult result =
                automation.publishPreparedSession(session);

        assertThat(result.status())
                .isEqualTo(YagaPublicationStatus.PUBLISHED);
        verify(firstResolution, never()).click();
        verify(secondResolution).click();
        verify(page, times(2)).getByRole(
                eq(AriaRole.BUTTON),
                any(Page.GetByRoleOptions.class)
        );
    }

    @Test
    void rejectsUnexpectedPublishedUrls() {
        PlaywrightYagaBrowserAutomation automation =
                automation();

        assertThat(automation.publishedResult(
                "http://www.yaga.ee/shop/toode/book",
                "shop"
        ).status()).isEqualTo(
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
        );
        assertThat(automation.publishedResult(
                "https://evil.example/shop/toode/book",
                "shop"
        ).status()).isEqualTo(
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
        );
        assertThat(automation.publishedResult(
                "https://www.yaga.ee/muuk/lisa-toode",
                "shop"
        ).status()).isEqualTo(
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
        );
        assertThat(automation.publishedResult(
                "https://www.yaga.ee/",
                "shop"
        ).status()).isEqualTo(
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
        );
        YagaPublishResult publicResult = automation.publishedResult(
                "https://yaga.ee/shop/toode/book",
                "fallback"
        );
        assertThat(publicResult.status())
                .isEqualTo(YagaPublicationStatus.PUBLISHED);
        assertThat(publicResult.productUrl())
                .isEqualTo("https://www.yaga.ee/shop/toode/book");

        YagaPublishResult intermediateResult =
                automation.publishedResult(
                        "https://www.yaga.ee/muuk/lisa-toode/book",
                        "shop"
                );
        assertThat(intermediateResult.status())
                .isEqualTo(
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN
                );
        assertThat(intermediateResult.productSlug()).isEqualTo("book");
        assertThat(intermediateResult.shopSlug()).isEqualTo("shop");
    }

    private PlaywrightYagaBrowserAutomation automation() {
        YagaPublishingProperties properties =
                new YagaPublishingProperties();
        return new PlaywrightYagaBrowserAutomation(properties);
    }

    private void mockVisibleConditionLabel(Page page, String selectedLabel) {
        Locator controls = mock(Locator.class);
        Locator selectedControl = mock(Locator.class);
        when(page.locator(
                "button, [role='button'], [role='combobox'], " +
                        "[aria-haspopup='listbox']"
        )).thenReturn(controls);
        when(controls.all()).thenReturn(List.of(selectedControl));
        when(selectedControl.isVisible()).thenReturn(true);
        when(selectedControl.innerText()).thenReturn(selectedLabel);

        for (String label : List.of(
                "Uus",
                "Uuev\u00e4\u00e4rne",
                "Hea",
                "Keskmine"
        )) {
            Locator locator = mock(Locator.class);
            Locator text = mock(Locator.class);
            when(page.getByText(
                    eq(label),
                    any(Page.GetByTextOptions.class)
            ))
                    .thenReturn(locator);

            if (label.equals(selectedLabel)) {
                when(locator.all()).thenReturn(List.of(text));
                when(locator.count()).thenReturn(1);
                when(text.isVisible()).thenReturn(true);
                when(text.textContent()).thenReturn(label);
            } else {
                when(locator.all()).thenReturn(List.of());
                when(locator.count()).thenReturn(0);
            }
        }
    }

    private String conditionSelectHtml(boolean secondVisibleListbox) {
        return """
                <button id="condition-trigger" type="button"
                        onclick="document.querySelector('#popup').style.display='block'">
                  Vali seisukord
                </button>
                <div id="global-option" role="option"
                     onclick="this.dataset.clicked='true'">Uuev&amp;auml;&amp;auml;rne</div>
                <ul id="hidden-popup" role="listbox" style="display:none">
                  <li id="hidden-option" role="option"
                      onclick="this.dataset.clicked='true'">Uuev&amp;auml;&amp;auml;rne</li>
                </ul>
                <ul id="popup" role="listbox" style="display:none">
                  <li>Uus</li>
                  <li id="visible-option" role="option" onclick="
                    this.dataset.clicked='true';
                    document.querySelector('#condition-trigger').textContent=this.textContent;
                    document.querySelector('#popup').style.display='none'">
                    Uuev&amp;auml;&amp;auml;rne
                  </li>
                  <li id="hea-option" role="option">Hea</li>
                  <li role="option">Keskmine</li>
                </ul>
                <ul role="listbox" style="display:%s">
                  <li role="option">Uuev&amp;auml;&amp;auml;rne</li>
                </ul>
                """.formatted(secondVisibleListbox ? "block" : "none")
                .replace("&amp;auml;", "&auml;");
    }

    private void withConditionPage(
            String options,
            java.util.function.Consumer<Page> assertion
    ) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent("""
                    <style>
                      .condition-line { display: block; }
                      .plain-lines { white-space: pre-line; }
                    </style>
                    <button id="condition-trigger" type="button" onclick="
                      document.querySelector('#condition-listbox').style.display='block'">
                      Vali seisukord
                    </button>
                    <ul id="condition-listbox" role="listbox" style="display:none">
                      %s
                    </ul>
                    <script>
                      document.querySelectorAll('#condition-listbox > *')
                          .forEach(option => option.addEventListener('click', event => {
                          option.dataset.clicked = 'true';
                          const trigger = document.querySelector('#condition-trigger');
                          trigger.innerHTML = option.innerHTML;
                          trigger.style.whiteSpace = 'pre-line';
                          document.querySelector('#condition-listbox').style.display = 'none';
                        }));
                    </script>
                    """.formatted(options));

            assertion.accept(page);
        }
    }

    private String localPreparedFormHtml(String conditionControl) {
        return """
                <textarea placeholder="Kirjelda toodet">Description</textarea>
                <div>
                  <span>Hind</span>
                  <input type="text" placeholder="0" value="17">
                </div>
                <div>Raamatud</div>
                %s
                <button type="button">Valmis</button>
                """.formatted(conditionControl);
    }

    private PlaywrightYagaBrowserAutomation.PlaywrightPreparedBrowserSession
    preparedSession(Page page, YagaListingDraftData draft) {
        return new PlaywrightYagaBrowserAutomation
                .PlaywrightPreparedBrowserSession(
                UUID.randomUUID(),
                draft,
                formResult(),
                mock(Playwright.class),
                mock(Browser.class),
                mock(BrowserContext.class),
                page
        );
    }

    private Locator publishButton(
            String text,
            boolean visible,
            boolean enabled,
            String tagName,
            String type
    ) {
        Locator button = mock(Locator.class);
        when(button.textContent()).thenReturn(text);
        when(button.isVisible()).thenReturn(visible);
        when(button.isEnabled()).thenReturn(enabled);
        when(button.evaluate(anyString(), eq("tagName")))
                .thenReturn(tagName);
        when(button.evaluate(anyString(), eq("type")))
                .thenReturn(type);
        when(button.evaluate(anyString()))
                .thenReturn("<button type=\"" +
                        type +
                        "\">" +
                        text +
                        "</button>");
        return button;
    }

    private void mockPublishRoleLocator(
            Page page,
            Locator buttons,
            Locator... buttonList
    ) {
        when(page.getByRole(
                eq(AriaRole.BUTTON),
                any(Page.GetByRoleOptions.class)
        ))
                .thenReturn(buttons);
        when(buttons.all()).thenReturn(List.of(buttonList));
    }

    private void mockConfirmedForm(Page page) {
        Locator description = mock(Locator.class);
        Locator descriptionInput = mock(Locator.class);
        Locator priceLabels = mock(Locator.class);
        Locator priceCandidates = mock(Locator.class);
        Locator priceInput = mock(Locator.class);
        Locator conditionText = mock(Locator.class);
        Locator categoryText = mock(Locator.class);

        when(page.getByPlaceholder("Kirjelda toodet"))
                .thenReturn(description);
        when(description.count()).thenReturn(1);
        when(description.first()).thenReturn(descriptionInput);
        when(descriptionInput.inputValue())
                .thenReturn("Description");

        when(page.getByText(any(Pattern.class)))
                .thenReturn(priceLabels);
        when(priceLabels.count()).thenReturn(0);
        when(page.locator("input[type='text'][placeholder='0']"))
                .thenReturn(priceCandidates);
        when(priceCandidates.count()).thenReturn(1);
        when(priceCandidates.nth(0)).thenReturn(priceInput);
        when(priceInput.isVisible()).thenReturn(true);
        when(priceInput.inputValue()).thenReturn("17");

        when(page.getByText(eq("Hea"), any(Page.GetByTextOptions.class)))
                .thenReturn(conditionText);
        when(conditionText.count()).thenReturn(1);
        when(conditionText.all()).thenReturn(List.of(conditionText));
        when(conditionText.isVisible()).thenReturn(true);
        when(conditionText.textContent()).thenReturn("Hea");
        when(page.getByText(
                eq("Raamatud"),
                any(Page.GetByTextOptions.class)
        ))
                .thenReturn(categoryText);
        when(categoryText.count()).thenReturn(1);
        mockVisibleConditionLabel(page, "Hea");
    }

    private YagaListingDraftData draft() {
        return new YagaListingDraftData(
                10L,
                1L,
                "shop",
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                ProductCondition.GOOD,
                List.of("Raamatud"),
                List.of(new YagaListingDraftData.Image(
                        "drive-1",
                        "image.jpg",
                        0,
                        true
                ))
        );
    }

    private YagaAccount account(Long id, String shopSlug) {
        YagaAccount account = new YagaAccount(
                "Account " + shopSlug,
                shopSlug,
                "../playwright/.auth/" + shopSlug + ".json",
                10
        );
        account.setId(id);
        return account;
    }

    private YagaFormFillResult formResult() {
        return new YagaFormFillResult(
                1,
                true,
                List.of("Raamatud"),
                "Hea",
                new BigDecimal("17.00"),
                Path.of("screenshot.png")
        );
    }

    private void mockDiagnostics(
            Page page,
            String url,
            String title,
            int descriptionCount,
            int categoryCount,
            int loginCount
    ) {
        Locator description = mock(Locator.class);
        Locator category = mock(Locator.class);
        Locator login = mock(Locator.class);

        when(page.url()).thenReturn(url);
        when(page.title()).thenReturn(title);
        when(page.getByPlaceholder("Kirjelda toodet"))
                .thenReturn(description);
        when(description.count()).thenReturn(descriptionCount);
        when(page.getByText(any(Pattern.class)))
                .thenReturn(category, login, category, login);
        when(category.count()).thenReturn(categoryCount);
        when(login.count()).thenReturn(loginCount);
        when(page.locator("input[type='text'][placeholder='0']"))
                .thenReturn(mock(Locator.class));
    }
}
