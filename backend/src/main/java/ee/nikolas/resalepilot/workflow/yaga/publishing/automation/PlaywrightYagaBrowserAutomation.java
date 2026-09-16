package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaConditionMapper;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaConditionSelection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedBrowserSession;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedImageFile;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublishedUrl;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublishedUrlResolver;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountAuthStateResolver;
import ee.nikolas.resalepilot.workflow.yaga.auth.YagaPlaywrightAuthState;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingOperationStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
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
    private static final List<String> CONDITION_LABELS =
            List.of("Uus", "Uuev\u00e4\u00e4rne", "Hea", "Keskmine");
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
    private static final String PUBLISH_BUTTON_TEXT = "Valmis";
    private static final YagaPublishedUrlResolver PUBLISHED_URL_RESOLVER =
            new YagaPublishedUrlResolver();

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
        return prepareForm(
                draft,
                imageFiles,
                accountFromDraft(draft)
        );
    }

    @Override
    public YagaFormFillResult prepareForm(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles,
            YagaAccount account
    ) {
        YagaPreparedBrowserSession session =
                prepareSession(draft, imageFiles, account);

        try {
            YagaFormFillResult result =
                    session.preparedForm();
            pageFrom(session).pause();
            return result;

        } finally {
            closeSession(session);
        }
    }

    @Override
    public YagaPreparedBrowserSession prepareSession(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    ) {
        return prepareSession(
                draft,
                imageFiles,
                accountFromDraft(draft)
        );
    }

    @Override
    public YagaPreparedBrowserSession prepareSession(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles,
            YagaAccount account
    ) {
        validateAccount(draft, account);
        Path authStatePath = YagaAccountAuthStateResolver.resolve(
                account,
                properties.getAuthStatePath()
        );

        if (Files.notExists(authStatePath)) {
            throw new YagaPublishingAuthException(
                    "Yaga auth state file is missing",
                    YagaPublishingFormDiagnostics.failureMetadata(
                            YagaPublishingOperationStage.RESOLVE_AUTH_STATE
                                    .name(),
                            YagaPublishingAuthException.class.getName(),
                            YagaPublishingAuthException.class.getName(),
                            "AUTH_STATE_FILE_MISSING"
                    )
            );
        }

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;

        try {
            playwright = Playwright.create();
            browser = launchBrowser(playwright);
            context = browser.newContext(
                    YagaPlaywrightAuthState.contextOptions(authStatePath)
             );
            YagaPlaywrightAuthState.restoreSessionStorage(
                    context,
                    authStatePath
            );

            Page page = context.newPage();

            try {
                runAtStage(
                        page,
                        YagaPublishingOperationStage.OPEN_FORM,
                        "YAGA_FORM_UNAVAILABLE",
                        "Yaga listing form is not accessible",
                        () -> {
                            page.navigate(properties.getFormUrl());
                            page.waitForLoadState(
                                    LoadState.DOMCONTENTLOADED
                            );
                            ensureFormAccessible(page);
                        }
                );
                runAtStage(
                        page,
                        YagaPublishingOperationStage.UPLOAD_IMAGES,
                        "IMAGE_UPLOAD_FAILED",
                        "Yaga image upload preparation failed",
                        () -> uploadImages(page, imageFiles)
                );
                runAtStage(
                        page,
                        YagaPublishingOperationStage.FILL_DESCRIPTION,
                        "DESCRIPTION_FILL_FAILED",
                        "Yaga description could not be filled",
                        () -> fillDescription(page, draft.description())
                );
                runAtStage(
                        page,
                        YagaPublishingOperationStage.SELECT_CATEGORY,
                        "CATEGORY_SELECTION_FAILED",
                        "Yaga category could not be selected",
                        () -> selectCategoryPath(
                                page,
                                draft.categoryPath()
                        )
                );

                YagaConditionSelection conditionSelection =
                        YagaConditionMapper.toYaga(draft.condition());

                selectCondition(page, conditionSelection);
                runAtStage(
                        page,
                        YagaPublishingOperationStage.FILL_PRICE,
                        "PRICE_FILL_FAILED",
                        "Yaga price could not be filled",
                        () -> fillPrice(page, draft.askingPrice())
                );

                YagaFormFillResult result = confirmFilledForm(
                        page,
                        draft,
                        conditionSelection,
                        imageFiles.size()
                );

                Path screenshotPath = callAtStage(
                        page,
                        YagaPublishingOperationStage.INSPECT_READINESS,
                        "FORM_READINESS_CAPTURE_FAILED",
                        "Yaga listing form readiness capture failed",
                        () -> takeScreenshot(
                                page,
                                draft.listingId()
                        )
                );

                YagaFormFillResult preparedForm =
                        new YagaFormFillResult(
                        result.imageCount(),
                        result.descriptionFilled(),
                        result.categoryPath(),
                        result.conditionLabel(),
                        result.price(),
                        screenshotPath
                );

                return new PlaywrightPreparedBrowserSession(
                        UUID.randomUUID(),
                        account.getId(),
                        draft,
                        preparedForm,
                        playwright,
                        browser,
                        context,
                        page
                );

            } catch (YagaPublishingAuthException exception) {
                throw enrichAuthFailure(page, exception);

            } catch (YagaPublishingFormException exception) {
                throw enrichFormFailure(page, exception);
            }

        } catch (YagaPublishingAuthException |
                 YagaPublishingFormException exception) {
            closeQuietly(context, browser, playwright);
            throw exception;

        } catch (RuntimeException exception) {
            closeQuietly(context, browser, playwright);
            throw stageFailure(
                    null,
                    YagaPublishingOperationStage.OPEN_FORM,
                    "YAGA_FORM_PREPARATION_FAILED",
                    "Failed to prepare Yaga listing form",
                    exception
            );
        }
    }

    private void runAtStage(
            Page page,
            YagaPublishingOperationStage stage,
            String safeErrorCode,
            String safeMessage,
            Runnable operation
    ) {
        callAtStage(
                page,
                stage,
                safeErrorCode,
                safeMessage,
                () -> {
                    operation.run();
                    return null;
                }
        );
    }

    private <T> T callAtStage(
            Page page,
            YagaPublishingOperationStage stage,
            String safeErrorCode,
            String safeMessage,
            Supplier<T> operation
    ) {
        try {
            return operation.get();
        } catch (YagaPublishingAuthException exception) {
            throw exception;
        } catch (YagaPublishingFormException exception) {
            if (exception.getDiagnostics() != null &&
                    exception.getDiagnostics().safeErrorCode() != null) {
                throw exception;
            }
            throw stageFailure(
                    page,
                    stage,
                    safeErrorCode,
                    exception.getMessage(),
                    exception
            );
        } catch (RuntimeException exception) {
            throw stageFailure(
                    page,
                    stage,
                    safeErrorCode,
                    safeMessage,
                    exception
            );
        }
    }

    private YagaPublishingFormException stageFailure(
            Page page,
            YagaPublishingOperationStage stage,
            String safeErrorCode,
            String safeMessage,
            RuntimeException exception
    ) {
        YagaPublishingFormDiagnostics diagnostics =
                exception instanceof YagaPublishingFormException form &&
                        form.getDiagnostics() != null
                        ? form.getDiagnostics()
                        : page == null
                        ? YagaPublishingFormDiagnostics.failureMetadata(
                                null, null, null, null
                        )
                        : collectDiagnostics(page, null);
        Throwable diagnosticCause = exception.getCause() == null
                ? exception
                : exception.getCause();
        diagnostics = diagnostics.withFailureMetadata(
                stage.name(),
                diagnosticCause.getClass().getName(),
                rootCauseClass(diagnosticCause),
                safeErrorCode
        );
        return new YagaPublishingFormException(
                safeMessage,
                diagnostics,
                exception
        );
    }

    private String rootCauseClass(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName();
    }

    @Override
    public YagaFormFillResult verifyPreparedForm(
            YagaPreparedBrowserSession session
    ) {
        PlaywrightPreparedBrowserSession playwrightSession =
                castSession(session);

        YagaConditionSelection conditionSelection =
                YagaConditionMapper.toYaga(
                        session.draft().condition()
                );

        Page page = playwrightSession.page();
        return callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_READINESS,
                "FORM_READINESS_INSPECTION_FAILED",
                "Yaga listing form readiness could not be confirmed",
                () -> confirmFilledForm(
                        page,
                        session.draft(),
                        conditionSelection,
                        session.draft().images().size()
                )
        );
    }

    @Override
    public YagaPublishControlInspection inspectPublishControl(
            YagaPreparedBrowserSession session
    ) {
        PlaywrightPreparedBrowserSession playwrightSession =
                castSession(session);
        Page page = playwrightSession.page();

        boolean formStillValid = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "FORM_VALIDITY_INSPECTION_FAILED",
                "Yaga listing form validity could not be confirmed",
                () -> isPreparedFormStillValid(page, session.draft())
        );
        return callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_PUBLISH_BUTTON,
                "PUBLISH_BUTTON_INSPECTION_FAILED",
                "Yaga publish button could not be inspected",
                () -> inspectPublishControl(page, formStillValid)
                        .inspection()
        );
    }

    @Override
    public YagaPublishResult publishPreparedSession(
            YagaPreparedBrowserSession session
    ) {
        PlaywrightPreparedBrowserSession playwrightSession =
                castSession(session);
        Page page = playwrightSession.page();

        boolean formStillValid = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "FORM_VALIDITY_INSPECTION_FAILED",
                "Yaga listing form validity could not be confirmed",
                () -> isPreparedFormStillValid(page, session.draft())
        );
        PublishControlResolution publishControl = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_PUBLISH_BUTTON,
                "PUBLISH_BUTTON_INSPECTION_FAILED",
                "Yaga publish button could not be inspected",
                () -> inspectPublishControl(page, formStillValid)
        );

        if (!publishControl.inspection().readyForConfirmation() ||
                publishControl.button() == null) {
            throw new YagaPublishingFormException(
                    "Yaga publish button is not uniquely available",
                    collectDiagnostics(page, null)
            );
        }

        tryTakeAuditScreenshot(
                page,
                "yaga-before-publish-" +
                        session.draft().listingId() +
                        "-" +
                        System.currentTimeMillis() +
                        ".png"
        );

        publishControl.button().click();

        try {
            page.waitForURL(
                    url -> !url.contains(CREATE_FORM_PATH),
                    new Page.WaitForURLOptions()
                            .setTimeout(FORM_TIMEOUT_MS)
            );
        } catch (RuntimeException exception) {
            return new YagaPublishResult(
                    true,
                    YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                    page.url(),
                    null,
                    null,
                    java.time.Instant.now()
            );
        }

        return publishedResult(page.url(), session.draft().shopSlug());
    }

    @Override
    public void closeSession(YagaPreparedBrowserSession session) {
        PlaywrightPreparedBrowserSession playwrightSession =
                castSession(session);

        closeQuietly(
                playwrightSession.context(),
                playwrightSession.browser(),
                playwrightSession.playwright()
        );
    }

    private Browser launchBrowser(Playwright playwright) {
        return playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setChannel("chrome")
                        .setHeadless(properties.isHeadless())
                        .setSlowMo(properties.getSlowMoMs())
        );
    }

    private void validateAccount(
            YagaListingDraftData draft,
            YagaAccount account
    ) {
        if (account == null ||
                !account.getId().equals(draft.yagaAccountId()) ||
                !account.getShopSlug().equals(draft.shopSlug())) {
            throw new YagaPublishingFormException(
                    "Yaga account does not match publication draft"
            );
        }
    }

    private YagaAccount accountFromDraft(YagaListingDraftData draft) {
        YagaAccount account = new YagaAccount(
                "Yaga account",
                draft.shopSlug(),
                null,
                10
        );
        account.setId(draft.yagaAccountId());
        return account;
    }

    private Page pageFrom(YagaPreparedBrowserSession session) {
        return castSession(session).page();
    }

    private PlaywrightPreparedBrowserSession castSession(
            YagaPreparedBrowserSession session
    ) {
        if (!(session instanceof PlaywrightPreparedBrowserSession casted)) {
            throw new IllegalArgumentException(
                    "Unsupported Yaga browser session"
            );
        }

        return casted;
    }

    private void closeQuietly(
            BrowserContext context,
            Browser browser,
            Playwright playwright
    ) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (RuntimeException ignored) {
        }

        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException ignored) {
        }

        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException ignored) {
        }
    }

    void ensureFormAccessible(Page page) {
        YagaPublishingFormDiagnostics beforeWait =
                collectDiagnostics(page, null);

        if (isAuthFailure(beforeWait)) {
            throw authSessionFailure(beforeWait, null);
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
                throw authSessionFailure(diagnostics, exception);
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
            throw authSessionFailure(diagnostics, null);
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

    void selectCondition(
            Page page,
            YagaConditionSelection selection
    ) {
        ElementHandle conditionControl = callAtStage(
                page,
                YagaPublishingOperationStage.OPEN_CONDITION,
                "CONDITION_DROPDOWN_UNAVAILABLE",
                "Yaga condition selector could not be opened",
                () -> openConditionDropdown(page, selection.label())
        );
        Locator option = callAtStage(
                page,
                YagaPublishingOperationStage.RESOLVE_CONDITION_OPTION,
                "CONDITION_OPTION_NOT_UNIQUE",
                "Yaga condition option is not uniquely available",
                () -> uniqueVisibleEnabledConditionOption(
                        page,
                        selection.label()
                )
        );
        runAtStage(
                page,
                YagaPublishingOperationStage.CLICK_CONDITION,
                "CONDITION_CLICK_FAILED",
                "Yaga condition option could not be selected",
                option::click
        );
        runAtStage(
                page,
                YagaPublishingOperationStage.VERIFY_CONDITION,
                "CONDITION_SELECTION_NOT_CONFIRMED",
                "Yaga condition selection was not confirmed",
                () -> waitForConditionValue(
                        page,
                        conditionControl,
                        selection.label()
                )
        );
    }

    private ElementHandle openConditionDropdown(
            Page page,
            String expectedLabel
    ) {
        List<Locator> roleTriggers = safeAll(page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(CONDITION)
        )).stream()
                .filter(this::safeIsVisible)
                .filter(this::safeIsEnabled)
                .toList();
        if (roleTriggers.size() == 1) {
            Locator trigger = roleTriggers.iterator().next();
            ElementHandle control = trigger.elementHandle();
            trigger.click();
            return control;
        }

        List<Locator> textTriggers = safeAll(
                page.getByText(CONDITION)
        ).stream()
                .filter(this::safeIsVisible)
                .filter(this::safeIsEnabled)
                .toList();
        if (textTriggers.size() == 1) {
            Locator trigger = textTriggers.iterator().next();
            ElementHandle control = trigger.elementHandle();
            trigger.click();
            return control;
        }

        throw conditionOptionNotUnique(
                page,
                expectedLabel,
                ConditionOptionResolution.empty(0),
                null
        );
    }

    void fillPrice(
            Page page,
            BigDecimal price
    ) {
        runAtStage(
                page,
                YagaPublishingOperationStage.FILL_PRICE,
                "PRICE_FILL_FAILED",
                "Yaga price could not be filled",
                () -> fillPriceValue(page, price)
        );
    }

    private void fillPriceValue(
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
        String descriptionValue = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "DESCRIPTION_VALIDITY_INSPECTION_FAILED",
                "Yaga description value could not be inspected",
                () -> safeInputValue(descriptionField(page).first())
        );

        String priceValue = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "PRICE_VALIDITY_INSPECTION_FAILED",
                "Yaga price value could not be inspected",
                () -> safeInputValue(priceField(page))
        );

        String actualConditionLabel = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_CONDITION,
                "CONDITION_VALIDITY_INSPECTION_FAILED",
                "Yaga condition value could not be inspected",
                () -> readSelectedConditionLabel(
                        page,
                        conditionSelection.label()
                )
        );
        verifyConditionSelection(
                page,
                conditionSelection.label(),
                actualConditionLabel
        );

        callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "CATEGORY_VALIDITY_INSPECTION_FAILED",
                "Yaga category values could not be inspected",
                () -> {
                    for (String category : draft.categoryPath()) {
                        confirmTextVisible(page, category);
                    }
                    return null;
                }
        );

        BigDecimal normalizedPrice = callAtStage(
                page,
                YagaPublishingOperationStage.INSPECT_FORM_VALIDITY,
                "PRICE_VALIDITY_INSPECTION_FAILED",
                "Yaga price value could not be inspected",
                () -> normalizePrice(priceValue)
        );

        boolean descriptionFilled = draft.description() != null &&
                draft.description().equals(descriptionValue);

        return new YagaFormFillResult(
                imageCount,
                descriptionFilled,
                List.copyOf(draft.categoryPath()),
                actualConditionLabel,
                normalizedPrice,
                null
        );
    }

    private void verifyConditionSelection(
            Page page,
            String expectedConditionLabel,
            String actualConditionLabel
    ) {
        if (expectedConditionLabel.equals(actualConditionLabel)) {
            return;
        }

        YagaPublishingFormDiagnostics diagnostics = collectDiagnostics(
                page,
                null,
                null,
                expectedConditionLabel,
                actualConditionLabel
        ).withFailureMetadata(
                YagaPublishingOperationStage.INSPECT_CONDITION.name(),
                YagaPublishingFormException.class.getName(),
                YagaPublishingFormException.class.getName(),
                "CONDITION_SELECTION_NOT_CONFIRMED"
        );
        throw new YagaPublishingFormException(
                "Yaga condition selection was not confirmed",
                diagnostics
        );
    }

    Locator uniqueVisibleEnabledConditionOption(
            Page page,
            String expectedLabel
    ) {
        try {
            page.waitForCondition(
                    () -> safeAll(page.locator("[role='listbox']"))
                            .stream()
                            .anyMatch(this::safeIsVisible),
                    new Page.WaitForConditionOptions()
                            .setTimeout(FORM_TIMEOUT_MS)
            );
        } catch (RuntimeException exception) {
            ConditionOptionResolution resolution =
                    resolveConditionOptions(page, expectedLabel);
            throw conditionOptionNotUnique(
                    page,
                    expectedLabel,
                    resolution,
                    exception
            );
        }

        ConditionOptionResolution resolution =
                resolveConditionOptions(page, expectedLabel);
        if (resolution.visibleListboxCount() != 1 ||
                resolution.enabledVisibleExactOptionLabelMatchCount() != 1 ||
                resolution.uniqueClickableContainer() == null) {
            throw conditionOptionNotUnique(
                    page,
                    expectedLabel,
                    resolution,
                    null
            );
        }

        return resolution.uniqueClickableContainer();
    }

    private YagaPublishingFormException conditionOptionNotUnique(
            Page page,
            String expectedLabel,
            ConditionOptionResolution resolution,
            RuntimeException cause
    ) {
        YagaPublishingFormDiagnostics diagnostics = collectDiagnostics(
                page,
                null,
                null,
                expectedLabel,
                readSelectedConditionLabel(page, expectedLabel),
                resolution
        );

        if (cause == null) {
            return new YagaPublishingFormException(
                    "Yaga condition option is not uniquely available",
                    diagnostics
            );
        }

        return new YagaPublishingFormException(
                "Yaga condition option is not uniquely available",
                diagnostics,
                cause
        );
    }

    private ConditionOptionResolution resolveConditionOptions(
            Page page,
            String expectedLabel
    ) {
        List<Locator> listboxes = safeAll(
                page.locator("[role='listbox']")
        );
        List<Locator> visibleListboxes = listboxes.stream()
                .filter(this::safeIsVisible)
                .toList();

        if (visibleListboxes.size() != 1) {
            return ConditionOptionResolution.empty(
                    visibleListboxes.size()
            );
        }

        Locator visibleListbox = visibleListboxes.iterator().next();
        List<Locator> containers = semanticConditionContainers(
                visibleListbox
        );
        List<ResolvedConditionContainer> resolvedContainers = containers
                .stream()
                .map(container -> new ResolvedConditionContainer(
                        container,
                        conditionOptionLabel(container),
                        safeIsVisible(container),
                        safeIsEnabled(container),
                        safeSemanticRole(container)
                ))
                .toList();
        List<ResolvedConditionContainer> exactMatches = resolvedContainers
                .stream()
                .filter(candidate -> expectedLabel.equals(candidate.label()))
                .toList();
        List<ResolvedConditionContainer> enabledVisibleExactMatches =
                exactMatches.stream()
                        .filter(ResolvedConditionContainer::visible)
                        .filter(ResolvedConditionContainer::enabled)
                        .toList();
        Locator uniqueClickableContainer =
                enabledVisibleExactMatches.size() == 1
                ? enabledVisibleExactMatches.iterator().next().container()
                : null;

        return new ConditionOptionResolution(
                1,
                resolvedContainers.size(),
                (int) resolvedContainers.stream()
                        .filter(ResolvedConditionContainer::visible)
                        .count(),
                (int) resolvedContainers.stream()
                        .filter(ResolvedConditionContainer::enabled)
                        .count(),
                exactMatches.size(),
                (int) exactMatches.stream()
                        .filter(ResolvedConditionContainer::visible)
                        .count(),
                enabledVisibleExactMatches.size(),
                uniqueClickableContainer,
                resolvedContainers.stream()
                        .map(ResolvedConditionContainer::role)
                        .distinct()
                        .toList(),
                resolvedContainers.stream()
                        .map(ResolvedConditionContainer::label)
                        .filter(this::isKnownConditionLabel)
                        .distinct()
                        .toList()
        );
    }

    private List<Locator> semanticConditionContainers(
            Locator listbox
    ) {
        List<Locator> roleOptions = safeAll(
                listbox.locator("[role='option']")
        ).stream()
                .filter(this::isOutermostRoleOption)
                .toList();
        if (!roleOptions.isEmpty()) {
            return roleOptions;
        }

        List<Locator> directSemanticItems = safeAll(listbox.locator(
                ":scope > li, :scope > button, :scope > [role='menuitem']"
        ));
        if (!directSemanticItems.isEmpty()) {
            return directSemanticItems;
        }

        List<Locator> repeatedConditionItems = safeAll(
                listbox.locator(":scope > *")
        ).stream()
                .filter(item -> isKnownConditionLabel(
                        conditionOptionLabel(item)
                ))
                .toList();
        return repeatedConditionItems.size() >= 2
                ? repeatedConditionItems
                : List.of();
    }

    private boolean isOutermostRoleOption(Locator option) {
        try {
            return Boolean.TRUE.equals(option.evaluate("""
                    element => {
                      const listbox = element.closest('[role="listbox"]');
                      if (!listbox) return false;
                      let parent = element.parentElement;
                      while (parent && parent !== listbox) {
                        if (parent.getAttribute('role') === 'option') {
                          return false;
                        }
                        parent = parent.parentElement;
                      }
                      return parent === listbox;
                    }
                    """));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String conditionOptionLabel(Locator container) {
        return firstNonEmptyLine(safeInnerText(container));
    }

    private String firstNonEmptyLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            String normalized = normalizeWhitespace(line);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return null;
    }

    private String safeSemanticRole(Locator container) {
        try {
            String role = container.getAttribute("role");
            if (role != null && !role.isBlank()) {
                return role.toLowerCase(Locale.ROOT);
            }
            Object tagName = container.evaluate(
                    "element => element.tagName.toLowerCase()"
            );
            return tagName == null ? "unknown" : tagName.toString();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private void waitForConditionValue(
            Page page,
            ElementHandle conditionControl,
            String expectedLabel
    ) {
        try {
            page.waitForCondition(
                    () -> expectedLabel.equals(
                            readSelectedConditionLabel(
                                    page,
                                    conditionControl,
                                    expectedLabel
                            )
                    ),
                    new Page.WaitForConditionOptions()
                            .setTimeout(FORM_TIMEOUT_MS)
            );
        } catch (RuntimeException exception) {
            String actualConditionLabel =
                    readSelectedConditionLabel(
                            page,
                            conditionControl,
                            expectedLabel
                    );
            throw new YagaPublishingFormException(
                    "Yaga condition selection was not confirmed",
                    collectDiagnostics(
                            page,
                            null,
                            null,
                            expectedLabel,
                            actualConditionLabel
                    ),
                    exception
            );
        }
    }

    String readSelectedConditionLabel(
            Page page,
            String expectedLabel
    ) {
        return readSelectedConditionLabel(page, null, expectedLabel);
    }

    private String readSelectedConditionLabel(
            Page page,
            ElementHandle conditionControl,
            String expectedLabel
    ) {
        String controlLabel = conditionControlLabel(conditionControl);
        if (controlLabel != null) {
            return controlLabel;
        }

        List<String> selectedControlLabels = safeAll(page.locator(
                "button, [role='button'], [role='combobox'], " +
                        "[aria-haspopup='listbox']"
        )).stream()
                .filter(this::safeIsVisible)
                .map(this::conditionOptionLabel)
                .filter(this::isKnownConditionLabel)
                .distinct()
                .toList();

        if (selectedControlLabels.size() == 1) {
            return selectedControlLabels.getFirst();
        }

        return null;
    }

    private String conditionControlLabel(ElementHandle conditionControl) {
        if (conditionControl == null) {
            return null;
        }
        try {
            String label = firstNonEmptyLine(conditionControl.innerText());
            return isKnownConditionLabel(label) ? label : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isKnownConditionLabel(String label) {
        return label != null && CONDITION_LABELS.contains(label);
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

            if (Files.notExists(screenshotPath)) {
                throw new YagaPublishingFormException(
                        "Yaga form screenshot was not created"
                );
            }

            return screenshotPath;

        } catch (Exception exception) {
            throw new YagaPublishingFormException(
                    "Failed to create Yaga form screenshot",
                    exception
            );
        }
    }

    private void tryTakeAuditScreenshot(
            Page page,
            String fileName
    ) {
        try {
            takeScreenshot(page, fileName);
        } catch (RuntimeException exception) {
            log.warn("Yaga publish audit screenshot was not created");
        }
    }

    YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath
    ) {
        return collectDiagnostics(page, screenshotPath, null, null, null);
    }

    YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath,
            String priceInputValue
    ) {
        return collectDiagnostics(page, screenshotPath, priceInputValue, null, null);
    }

    YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath,
            String priceInputValue,
            String expectedConditionLabel,
            String actualConditionLabel
    ) {
        return collectDiagnostics(
                page,
                screenshotPath,
                priceInputValue,
                expectedConditionLabel,
                actualConditionLabel,
                null
        );
    }

    private YagaPublishingFormDiagnostics collectDiagnostics(
            Page page,
            Path screenshotPath,
            String priceInputValue,
            String expectedConditionLabel,
            String actualConditionLabel,
            ConditionOptionResolution conditionResolution
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
                priceInputValue,
                expectedConditionLabel,
                actualConditionLabel,
                conditionResolution == null
                        ? 0
                        : conditionResolution.visibleListboxCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.exactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .visibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .enabledVisibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.exactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .visibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .semanticContainerCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .enabledVisibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? List.of()
                        : conditionResolution.candidateRoles(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.semanticContainerCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.visibleSemanticContainerCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.enabledSemanticContainerCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution.exactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .visibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? 0
                        : conditionResolution
                        .enabledVisibleExactOptionLabelMatchCount(),
                conditionResolution == null
                        ? List.of()
                        : conditionResolution.optionLabelExamples(),
                null,
                null,
                null,
                null
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
            log.warn("Failed to capture Yaga prepare-form failure screenshot");
        }

        YagaPublishingFormDiagnostics diagnostics =
                collectDiagnostics(
                        page,
                        screenshotPath,
                        existingDiagnostics == null
                                ? null
                                : existingDiagnostics.priceInputValue(),
                        existingDiagnostics == null
                                ? null
                                : existingDiagnostics.expectedConditionLabel(),
                        existingDiagnostics == null
                                ? null
                                : existingDiagnostics.actualConditionLabel(),
                        existingDiagnostics == null
                                ? null
                                : new ConditionOptionResolution(
                                existingDiagnostics.visibleListboxCount(),
                                existingDiagnostics
                                        .semanticContainerCount(),
                                existingDiagnostics
                                        .visibleSemanticContainerCount(),
                                existingDiagnostics
                                        .enabledSemanticContainerCount(),
                                existingDiagnostics
                                        .exactOptionLabelMatchCount(),
                                existingDiagnostics
                                        .visibleExactOptionLabelMatchCount(),
                                existingDiagnostics
                                        .enabledVisibleExactOptionLabelMatchCount(),
                                null,
                                existingDiagnostics.candidateRoles(),
                                existingDiagnostics.optionLabelExamples()
                        )
                );

        if (existingDiagnostics != null &&
                existingDiagnostics.safeErrorCode() != null) {
            diagnostics = diagnostics.withFailureMetadata(
                    existingDiagnostics.operationStage(),
                    existingDiagnostics.exceptionClass(),
                    existingDiagnostics.rootCauseClass(),
                    existingDiagnostics.safeErrorCode()
            );
        }

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

    private YagaPublishingAuthException authSessionFailure(
            YagaPublishingFormDiagnostics diagnostics,
            RuntimeException cause
    ) {
        YagaPublishingFormDiagnostics enriched =
                diagnostics.withFailureMetadata(
                        YagaPublishingOperationStage.OPEN_FORM.name(),
                        YagaPublishingAuthException.class.getName(),
                        cause == null
                                ? YagaPublishingAuthException.class.getName()
                                : rootCauseClass(cause),
                        "AUTH_SESSION_INVALID"
                );
        if (cause == null) {
            return new YagaPublishingAuthException(
                    "Yaga session is expired or not authorized",
                    enriched
            );
        }
        return new YagaPublishingAuthException(
                "Yaga session is expired or not authorized",
                enriched,
                cause
        );
    }

    private void logDiagnostics(
            YagaPublishingFormDiagnostics diagnostics
    ) {
        log.warn(
                "Yaga prepare-form diagnostics: operationStage={}, " +
                        "safeErrorCode={}, exceptionClass={}, " +
                        "rootCauseClass={}, expectedConditionLabel={}, " +
                        "actualConditionLabel={}, visibleListboxCount={}, " +
                        "semanticContainerCount={}, " +
                        "visibleSemanticContainerCount={}, " +
                        "enabledSemanticContainerCount={}, " +
                        "exactOptionLabelMatchCount={}, " +
                        "visibleExactOptionLabelMatchCount={}, " +
                        "enabledVisibleExactOptionLabelMatchCount={}, " +
                        "optionLabelExamples={}",
                diagnostics.operationStage(),
                diagnostics.safeErrorCode(),
                diagnostics.exceptionClass(),
                diagnostics.rootCauseClass(),
                diagnostics.expectedConditionLabel(),
                diagnostics.actualConditionLabel(),
                diagnostics.visibleListboxCount(),
                diagnostics.semanticContainerCount(),
                diagnostics.visibleSemanticContainerCount(),
                diagnostics.enabledSemanticContainerCount(),
                diagnostics.exactOptionLabelMatchCount(),
                diagnostics.visibleExactOptionLabelMatchCount(),
                diagnostics.enabledVisibleExactOptionLabelMatchCount(),
                diagnostics.optionLabelExamples()
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

    PublishControlResolution inspectPublishControl(
            Page page,
            boolean formStillValid
    ) {
        List<Locator> candidates =
                publishControlCandidates(page);
        int candidateCount = 0;
        int visibleCandidateCount = 0;
        int enabledCandidateCount = 0;
        Locator readyButton = null;
        String buttonText = null;
        String tagName = null;
        String typeAttribute = null;

        for (Locator candidate : candidates) {
            String candidateText =
                    normalizeWhitespace(safeText(candidate));

            if (!PUBLISH_BUTTON_TEXT.equals(candidateText)) {
                continue;
            }

            candidateCount++;

            boolean visible = safeIsVisible(candidate);
            boolean enabled = safeIsEnabled(candidate);
            String candidateTagName =
                    safeAttribute(candidate, "tagName");
            String candidateType =
                    safeAttribute(candidate, "type");
            boolean safeType = candidateType == null ||
                    candidateType.isBlank() ||
                    "button".equalsIgnoreCase(candidateType) ||
                    "submit".equalsIgnoreCase(candidateType);

            if (visible) {
                visibleCandidateCount++;
            }
            if (enabled) {
                enabledCandidateCount++;
            }

            if (candidateCount == 1) {
                buttonText = candidateText;
                tagName = candidateTagName;
                typeAttribute = candidateType;
            } else {
                buttonText = null;
                tagName = null;
                typeAttribute = null;
                readyButton = null;
            }

            if (candidateCount == 1 &&
                    visible &&
                    enabled &&
                    "button".equalsIgnoreCase(candidateTagName) &&
                    safeType) {
                readyButton = candidate;
            }
        }

        boolean readyForConfirmation =
                formStillValid &&
                        isCreateFormUrl(safeCurrentUrl(page)) &&
                        candidateCount == 1 &&
                        visibleCandidateCount == 1 &&
                        enabledCandidateCount == 1 &&
                        readyButton != null;

        if (!readyForConfirmation) {
            readyButton = null;
        }

        return new PublishControlResolution(
                readyButton,
                new YagaPublishControlInspection(
                        safeCurrentUrl(page),
                        formStillValid,
                        candidateCount,
                        visibleCandidateCount,
                        enabledCandidateCount,
                        buttonText,
                        tagName,
                        typeAttribute,
                        readyForConfirmation,
                        Instant.now()
                )
        );
    }

    private List<Locator> publishControlCandidates(Page page) {
        Locator roleButtons = page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName(PUBLISH_BUTTON_TEXT)
                        .setExact(true)
        );
        List<Locator> roleCandidates = safeAll(roleButtons);

        if (!roleCandidates.isEmpty()) {
            return roleCandidates;
        }

        try {
            Locator fallbackButtons = page
                    .locator("button[type='button']")
                    .filter(new Locator.FilterOptions()
                            .setHasText(Pattern.compile(
                                    "^\\s*" +
                                            PUBLISH_BUTTON_TEXT +
                                            "\\s*$"
                            )));

            return safeAll(fallbackButtons);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<Locator> safeAll(Locator locator) {
        if (locator == null) {
            return List.of();
        }

        try {
            return locator.all();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private boolean isCreateFormUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            URI uri = new URI(url);
            return CREATE_FORM_PATH.equals(uri.getPath());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isPreparedFormStillValid(
            Page page,
            YagaListingDraftData draft
    ) {
        try {
            YagaConditionSelection conditionSelection =
                    YagaConditionMapper.toYaga(draft.condition());
            YagaFormFillResult result =
                    confirmFilledForm(
                            page,
                            draft,
                            conditionSelection,
                            draft.images().size()
                    );

            return result.descriptionFilled() &&
                    result.categoryPath().equals(draft.categoryPath()) &&
                    result.price().compareTo(draft.askingPrice()) == 0;

        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean safeIsEnabled(Locator locator) {
        try {
            return locator.isEnabled();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String safeInputValue(Locator locator) {
        try {
            return locator.inputValue();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String safeText(Locator locator) {
        try {
            String text = locator.textContent();
            return text == null ? "" : text;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String safeInnerText(Locator locator) {
        try {
            String text = locator.innerText();
            return text == null ? "" : text;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeAttribute(
            Locator locator,
            String attribute
    ) {
        try {
            Object value = locator.evaluate(
                    "(element, attribute) => attribute === 'tagName' " +
                            "? element.tagName.toLowerCase() " +
                            ": element.getAttribute(attribute)",
                    attribute
            );
            return value == null ? null : value.toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    YagaPublishResult publishedResult(
            String url,
            String fallbackShopSlug
    ) {
        try {
            YagaPublishedUrl resolved =
                    PUBLISHED_URL_RESOLVER.resolve(
                            url,
                            fallbackShopSlug
                    );

            if (!resolved.publicProductUrl()) {
                return new YagaPublishResult(
                        true,
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                        url,
                        resolved.shopSlug(),
                        resolved.productSlug(),
                        java.time.Instant.now()
                );
            }

            return new YagaPublishResult(
                    true,
                    YagaPublicationStatus.PUBLISHED,
                    resolved.publicUrl(),
                    resolved.shopSlug(),
                    resolved.productSlug(),
                    java.time.Instant.now()
            );

        } catch (RuntimeException exception) {
            return unknownPublishResult(url);
        }
    }

    private YagaPublishResult unknownPublishResult(String url) {
        return new YagaPublishResult(
                true,
                YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN,
                url,
                null,
                null,
                java.time.Instant.now()
        );
    }

    record PublishControlResolution(
            Locator button,
            YagaPublishControlInspection inspection
    ) {
    }

    private record ConditionOptionResolution(
            int visibleListboxCount,
            int semanticContainerCount,
            int visibleSemanticContainerCount,
            int enabledSemanticContainerCount,
            int exactOptionLabelMatchCount,
            int visibleExactOptionLabelMatchCount,
            int enabledVisibleExactOptionLabelMatchCount,
            Locator uniqueClickableContainer,
            List<String> candidateRoles,
            List<String> optionLabelExamples
    ) {
        private static ConditionOptionResolution empty(
                int visibleListboxCount
        ) {
            return new ConditionOptionResolution(
                    visibleListboxCount,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    List.of(),
                    List.of()
            );
        }
    }

    private record ResolvedConditionContainer(
            Locator container,
            String label,
            boolean visible,
            boolean enabled,
            String role
    ) {
    }

    record PlaywrightPreparedBrowserSession(
            UUID sessionId,
            Long yagaAccountId,
            YagaListingDraftData draft,
            YagaFormFillResult preparedForm,
            Playwright playwright,
            Browser browser,
            BrowserContext context,
            Page page
    ) implements YagaPreparedBrowserSession {
        PlaywrightPreparedBrowserSession(
                UUID sessionId,
                YagaListingDraftData draft,
                YagaFormFillResult preparedForm,
                Playwright playwright,
                Browser browser,
                BrowserContext context,
                Page page
        ) {
            this(
                    sessionId,
                    draft.yagaAccountId(),
                    draft,
                    preparedForm,
                    playwright,
                    browser,
                    context,
                    page
            );
        }
    }
}
