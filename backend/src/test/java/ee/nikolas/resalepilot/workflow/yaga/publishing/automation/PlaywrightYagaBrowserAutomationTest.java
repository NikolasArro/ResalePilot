package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;

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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightYagaBrowserAutomationTest {

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

        assertThatThrownBy(() -> automation.ensureFormAccessible(page))
                .isInstanceOf(YagaPublishingAuthException.class)
                .hasMessageContaining("not authorized");

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
        when(page.getByText(
                eq("Raamatud"),
                any(Page.GetByTextOptions.class)
        ))
                .thenReturn(categoryText);
        when(categoryText.count()).thenReturn(1);
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
