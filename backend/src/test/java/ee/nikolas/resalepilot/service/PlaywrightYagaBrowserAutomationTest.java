package ee.nikolas.resalepilot.service;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import ee.nikolas.resalepilot.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private PlaywrightYagaBrowserAutomation automation() {
        YagaPublishingProperties properties =
                new YagaPublishingProperties();
        return new PlaywrightYagaBrowserAutomation(properties);
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
