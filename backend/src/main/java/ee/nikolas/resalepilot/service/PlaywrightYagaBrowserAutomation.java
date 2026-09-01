package ee.nikolas.resalepilot.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import ee.nikolas.resalepilot.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.exception.YagaPublishingFormDiagnostics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class PlaywrightYagaBrowserAutomation
        implements YagaBrowserAutomation {

    private static final Logger log =
            LoggerFactory.getLogger(
                    PlaywrightYagaBrowserAutomation.class
            );

    private static final Pattern DESCRIPTION =
            Pattern.compile("Kirjeldus", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE =
            Pattern.compile("Hind", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY =
            Pattern.compile("Vali kategooria", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBCATEGORY =
            Pattern.compile(
                    "Vali alamkategooria",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern CONDITION =
            Pattern.compile("Vali seisukord", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGIN =
            Pattern.compile(
                    "Logi sisse|Sisene|Login|Sign in",
                    Pattern.CASE_INSENSITIVE
            );
    private static final String DESCRIPTION_PLACEHOLDER =
            "Kirjelda toodet";
    private static final String CREATE_FORM_PATH =
            "/muuk/lisa-toode";
    private static final double FORM_TIMEOUT_MS = 15_000;
    private static final String PRICE_PLACEHOLDER_SELECTOR =
            "input[type='text'][placeholder='0']";

    private final YagaPublishingProperties properties;

    public PlaywrightYagaBrowserAutomation(
            YagaPublishingProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public YagaFormFillResult prepareForm(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    ) {
        Path authStatePath =
                Paths.get(properties.getAuthStatePath())
                        .toAbsolutePath()
                        .normalize();

        if (Files.notExists(authStatePath)) {
            throw new YagaPublishingAuthException(
                    "Yaga auth state file is missing"
            );
        }

        try (Playwright playwright = Playwright.create();
             Browser browser = launchBrowser(playwright);
             BrowserContext context = browser.newContext(
                     new Browser.NewContextOptions()
                             .setStorageStatePath(authStatePath)
                             .setViewportSize(1440, 900)
             )) {

            Page page = context.newPage();

            page.navigate(properties.getFormUrl());
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            try {
                ensureFormAccessible(page);
                uploadImages(page, imageFiles);
                fillDescription(page, draft.description());
                selectCategoryPath(page, draft.categoryPath());

                YagaConditionSelection conditionSelection =
                        YagaConditionMapper.toYaga(draft.condition());

                selectCondition(page, conditionSelection);
                fillPrice(page, draft.askingPrice());

                YagaFormFillResult result =
                        confirmFilledForm(
                                page,
                                draft,
                                conditionSelection,
                                imageFiles.size()
                        );

                Path screenshotPath = takeScreenshot(
                        page,
                        draft.listingId()
                );

                page.pause();

                return new YagaFormFillResult(
                        result.imageCount(),
                        result.descriptionFilled(),
                        result.categoryPath(),
                        result.conditionLabel(),
                        result.price(),
                        screenshotPath
                );

            } catch (YagaPublishingAuthException exception) {
                throw enrichAuthFailure(page, exception);

            } catch (YagaPublishingFormException exception) {
                throw enrichFormFailure(page, exception);
            }

        } catch (YagaPublishingAuthException |
                 YagaPublishingFormException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new YagaPublishingFormException(
                    "Failed to prepare Yaga listing form",
                    exception
            );
        }
    }

    private Browser launchBrowser(Playwright playwright) {
        return playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setChannel("chrome")
                        .setHeadless(properties.isHeadless())
                        .setSlowMo(properties.getSlowMoMs())
        );
    }

    void ensureFormAccessible(Page page) {
        YagaPublishingFormDiagnostics beforeWait =
                collectDiagnostics(page, null);

        if (isAuthFailure(beforeWait)) {
            throw new YagaPublishingAuthException(
                    "Yaga session is expired or not authorized",
                    beforeWait
            );
        }

        try {
            page.waitForSelector(
                    "textarea[placeholder=\"" +
                            DESCRIPTION_PLACEHOLDER +
                            "\"]",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(FORM_TIMEOUT_MS)
            );

        } catch (RuntimeException exception) {
            YagaPublishingFormDiagnostics diagnostics =
                    collectDiagnostics(page, null);

            if (isAuthFailure(diagnostics)) {
                throw new YagaPublishingAuthException(
                        "Yaga session is expired or not authorized",
                        diagnostics,
                        exception
                );
            }

            throw new YagaPublishingFormException(
                    "Yaga listing form is not accessible",
                    diagnostics,
                    exception
            );
        }

        YagaPublishingFormDiagnostics diagnostics =
                collectDiagnostics(page, null);

        if (isAuthFailure(diagnostics)) {
            throw new YagaPublishingAuthException(
                    "Yaga session is expired or not authorized",
                    diagnostics
            );
        }

        if (!diagnostics.productDescriptionPlaceholderVisible() ||
                !diagnostics.categorySelectorVisible()) {
            throw new YagaPublishingFormException(
                    "Yaga listing form is not accessible",
                    diagnostics
            );
        }
    }

    private void uploadImages(
            Page page,
            List<YagaPreparedImageFile> imageFiles
    ) {
        Path[] paths = imageFiles
                .stream()
                .map(YagaPreparedImageFile::path)
                .toArray(Path[]::new);

        Locator fileInput = page.locator("input[type='file']").first();
        fileInput.setInputFiles(paths);
    }

    private void fillDescription(
            Page page,
            String description
    ) {
        descriptionField(page)
                .first()
                .fill(description);
    }

    private void selectCategoryPath(
            Page page,
            List<String> categoryPath
    ) {
        openDropdown(page, CATEGORY);
        selectVisibleOption(page, categoryPath.getFirst());

        for (int index = 1; index < categoryPath.size(); index++) {
            openDropdown(page, SUBCATEGORY);
            selectVisibleOption(page, categoryPath.get(index));
        }
    }

    private void selectCondition(
            Page page,
            YagaConditionSelection selection
    ) {
        openDropdown(page, CONDITION);
        page.keyboard().press("Home");

        for (int index = 0;
             index < selection.arrowDownCount();
             index++) {
            page.keyboard().press("ArrowDown");
        }

        page.keyboard().press("Enter");
    }

    void fillPrice(
            Page page,
            BigDecimal price
    ) {
        Locator input = priceField(page);
        String priceText = plainPrice(price);

        input.scrollIntoViewIfNeeded();
        input.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(FORM_TIMEOUT_MS)
        );

        if (!input.isEditable()) {
            throw new YagaPublishingFormException(
                    "Yaga price field is not editable",
                    collectDiagnostics(page, null)
            );
        }

        input.click();
        input.fill(priceText);
        input.press("Tab");

        waitForPriceValue(page, input, price);
    }

    private YagaFormFillResult confirmFilledForm(
            Page page,
            YagaListingDraftData draft,
            YagaConditionSelection conditionSelection,
            int imageCount
    ) {
        String descriptionValue =
                descriptionField(page)
                        .first()
                        .inputValue();

        String priceValue = priceField(page).inputValue();

        confirmTextVisible(page, conditionSelection.label());

        for (String category : draft.categoryPath()) {
            confirmTextVisible(page, category);
        }

        return new YagaFormFillResult(
                imageCount,
                draft.description().equals(descriptionValue),
                List.copyOf(draft.categoryPath()),
                conditionSelection.label(),
                normalizePrice(priceValue),
                null
        );
    }

    Locator priceField(Page page) {
        Locator sectionInput = priceInputInsideHindContainer(page);

        if (sectionInput != null) {
            return sectionInput;
        }

        Locator placeholderCandidates =
                page.locator(PRICE_PLACEHOLDER_SELECTOR);
        List<Locator> visibleCandidates =
                visibleCandidates(placeholderCandidates);

        if (visibleCandidates.size() == 1) {
            return visibleCandidates.getFirst();
        }

        throw new YagaPublishingFormException(
                "Yaga price field is not accessible",
                collectDiagnostics(page, null)
        );
    }

    private Locator priceInputInsideHindContainer(Page page) {
        Locator labels = page.getByText(PRICE);

        for (int index = 0; index < safeCount(labels); index++) {
            Locator localInputs = labels
                    .nth(index)
                    .locator(
                            "xpath=ancestor::*[.//input[@type='text']][1]"
                    )
                    .locator("input[type='text']");

            List<Locator> visibleLocalInputs =
                    visibleCandidates(localInputs);

            if (visibleLocalInputs.size() == 1) {
                return visibleLocalInputs.getFirst();
            }
        }

        return null;
    }

    private List<Locator> visibleCandidates(Locator candidates) {
        List<Locator> visibleCandidates = new ArrayList<>();

        for (int index = 0; index < safeCount(candidates); index++) {
            Locator candidate = candidates.nth(index);

            if (safeIsVisible(candidate)) {
                visibleCandidates.add(candidate);
            }
        }

        return visibleCandidates;
    }

    private void waitForPriceValue(
            Page page,
            Locator input,
            BigDecimal expectedPrice
    ) {
        String inputValue = null;

        for (int attempt = 0; attempt < 15; attempt++) {
            inputValue = input.inputValue();

            if (pricesEqual(inputValue, expectedPrice)) {
                return;
            }

            page.waitForTimeout(100);
        }

        throw new YagaPublishingFormException(
                "Yaga price value was not confirmed in DOM",
                collectDiagnostics(page, null, inputValue)
        );
    }

    static BigDecimal normalizePrice(String value) {
        if (value == null) {
            throw new YagaPublishingFormException(
                    "Yaga price value is empty"
            );
        }

        String normalized = value
                .replace('\u00a0', ' ')
                .replace(',', '.')
                .replaceAll("[^0-9.\\-]", "")
                .trim();

        if (normalized.isBlank()) {
            throw new YagaPublishingFormException(
                    "Yaga price value is empty"
            );
        }

        return new BigDecimal(normalized)
                .setScale(2, RoundingMode.UNNECESSARY)
                .stripTrailingZeros();
    }

    private boolean pricesEqual(
            String actualValue,
            BigDecimal expectedPrice
    ) {
        try {
            return normalizePrice(actualValue)
                    .compareTo(normalizePrice(
                            expectedPrice.toPlainString()
                    )) == 0;
        } catch (YagaPublishingFormException |
                 ArithmeticException exception) {
            return false;
        }
    }

    private String plainPrice(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    private Path takeScreenshot(Page page, Long listingId) {
        return takeScreenshot(
                page,
                "yaga-prepare-form-listing-" +
                        listingId +
                        "-" +
                        System.currentTimeMillis() +
                        ".png"
        );
    }

    Path takeFailureScreenshot(Page page) {
        return takeScreenshot(
                page,
                "yaga-prepare-failure-" +
                        System.currentTimeMillis() +
                        ".png"
        );
    }

    private Path takeScreenshot(Page page, String fileName) {
        try {
            Path screenshotsDirectory =
                    Paths.get(
                                    "..",
                                    "playwright",
                                    "screenshots"
                            )
                            .toAbsolutePath()
                            .normalize();

            Files.createDirectories(screenshotsDirectory);

            Path screenshotPath =
                    screenshotsDirectory.resolve(fileName);

            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(screenshotPath)
                            .setFullPage(true)
            );

            return screenshotPath;

        } catch (Exception exception) {
            throw new YagaPublishingFormException(
                    "Failed to create Yaga form screenshot",
                    exception
            );
        }
    }

    YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath
    ) {
        return collectDiagnostics(page, screenshotPath, null);
    }

    YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath,
            String priceInputValue
    ) {
        String currentUrl = safeCurrentUrl(page);
        String pageTitle = safeTitle(page);
        boolean descriptionVisible =
                safeCount(page.getByPlaceholder(
                        DESCRIPTION_PLACEHOLDER
                )) > 0;
        boolean categoryVisible =
                safeCount(page.getByText(
                        CATEGORY
                )) > 0;
        boolean loginVisible =
                safeCount(page.getByText(
                        LOGIN
                )) > 0;
        Locator pricePlaceholderCandidates =
                page.locator(PRICE_PLACEHOLDER_SELECTOR);
        List<Locator> visiblePriceCandidates =
                visibleCandidates(pricePlaceholderCandidates);

        return new YagaPublishingFormDiagnostics(
                currentUrl,
                pageTitle,
                descriptionVisible,
                categoryVisible,
                loginVisible,
                screenshotPath,
                visiblePriceCandidates.size(),
                visiblePriceCandidates.stream()
                        .map(this::safeOuterHtmlWithoutValue)
                        .toList(),
                priceInputValue
        );
    }

    private YagaPublishingAuthException enrichAuthFailure(
            Page page,
            YagaPublishingAuthException exception
    ) {
        YagaPublishingFormDiagnostics diagnostics =
                diagnosticsWithFailureScreenshot(
                        page,
                        exception.getDiagnostics()
                );

        logDiagnostics(diagnostics);

        return new YagaPublishingAuthException(
                exception.getMessage(),
                diagnostics,
                exception
        );
    }

    private YagaPublishingFormException enrichFormFailure(
            Page page,
            YagaPublishingFormException exception
    ) {
        YagaPublishingFormDiagnostics diagnostics =
                diagnosticsWithFailureScreenshot(
                        page,
                        exception.getDiagnostics()
                );

        logDiagnostics(diagnostics);

        return new YagaPublishingFormException(
                exception.getMessage(),
                diagnostics,
                exception
        );
    }

    private YagaPublishingFormDiagnostics diagnosticsWithFailureScreenshot(
            Page page,
            YagaPublishingFormDiagnostics existingDiagnostics
    ) {
        Path screenshotPath = null;

        try {
            screenshotPath = takeFailureScreenshot(page);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to capture Yaga prepare-form failure screenshot: {}",
                    exception.getMessage()
            );
        }

        YagaPublishingFormDiagnostics diagnostics =
                collectDiagnostics(
                        page,
                        screenshotPath,
                        existingDiagnostics == null
                                ? null
                                : existingDiagnostics.priceInputValue()
                );

        if (screenshotPath == null &&
                existingDiagnostics != null) {
            return existingDiagnostics;
        }

        return diagnostics;
    }

    private boolean isAuthFailure(
            YagaPublishingFormDiagnostics diagnostics
    ) {
        String currentUrl =
                diagnostics.currentUrl() == null
                        ? ""
                        : diagnostics.currentUrl()
                        .toLowerCase(Locale.ROOT);

        return !currentUrl.contains(CREATE_FORM_PATH) ||
                diagnostics.loginElementVisible();
    }

    private void logDiagnostics(
            YagaPublishingFormDiagnostics diagnostics
    ) {
        log.warn(
                "Yaga prepare-form diagnostics: currentUrl={}, pageTitle={}, " +
                        "descriptionPlaceholderVisible={}, " +
                        "categorySelectorVisible={}, loginElementVisible={}, " +
                        "screenshotPath={}, visiblePricePlaceholderCandidateCount={}, " +
                        "pricePlaceholderCandidateOuterHtml={}, priceInputValue={}",
                diagnostics.currentUrl(),
                diagnostics.pageTitle(),
                diagnostics.productDescriptionPlaceholderVisible(),
                diagnostics.categorySelectorVisible(),
                diagnostics.loginElementVisible(),
                diagnostics.screenshotPath(),
                diagnostics.visiblePricePlaceholderCandidateCount(),
                diagnostics.pricePlaceholderCandidateOuterHtml(),
                diagnostics.priceInputValue()
        );
    }

    private String safeCurrentUrl(Page page) {
        try {
            return page.url();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String safeTitle(Page page) {
        try {
            return page.title();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int safeCount(Locator locator) {
        try {
            return locator.count();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private boolean safeIsVisible(Locator locator) {
        try {
            return locator.isVisible();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String safeOuterHtmlWithoutValue(Locator locator) {
        try {
            Object html = locator.evaluate("""
                    element => element.outerHTML
                        .replace(/value="[^"]*"/gi, 'value=""')
                        .replace(/value='[^']*'/gi, "value=''")
                    """);

            if (html == null) {
                return "";
            }

            String value = html.toString();
            return value.length() <= 500
                    ? value
                    : value.substring(0, 500);

        } catch (RuntimeException exception) {
            return "";
        }
    }

    private Locator fieldByLabelOrPlaceholder(
            Page page,
            Pattern label
    ) {
        Locator byLabel = page.getByLabel(label);

        if (byLabel.count() > 0) {
            return byLabel;
        }

        return page.getByPlaceholder(label);
    }

    private Locator descriptionField(Page page) {
        Locator byPlaceholder =
                page.getByPlaceholder(DESCRIPTION_PLACEHOLDER);

        if (byPlaceholder.count() > 0) {
            return byPlaceholder;
        }

        return fieldByLabelOrPlaceholder(page, DESCRIPTION);
    }

    private void openDropdown(
            Page page,
            Pattern label
    ) {
        Locator text = page.getByText(label);

        if (text.count() > 0) {
            text.last().click();
            return;
        }

        page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions()
                                .setName(label)
                )
                .last()
                .click();
    }

    private void selectVisibleOption(
            Page page,
            String option
    ) {
        Locator matchingOptions = page.getByText(
                option,
                new Page.GetByTextOptions()
                        .setExact(true)
        );

        if (matchingOptions.count() == 0) {
            throw new YagaPublishingFormException(
                    "Yaga option was not found: " + option
            );
        }

        matchingOptions.last().click();
    }

    private void confirmTextVisible(
            Page page,
            String text
    ) {
        if (page.getByText(
                        text,
                        new Page.GetByTextOptions()
                                .setExact(true)
                )
                .count() == 0) {
            throw new YagaPublishingFormException(
                    "Yaga form value was not confirmed: " + text
            );
        }
    }
}
