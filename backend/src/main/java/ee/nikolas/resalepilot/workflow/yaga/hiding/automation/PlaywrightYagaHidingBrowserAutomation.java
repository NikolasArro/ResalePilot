package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideResult;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideTargetDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaManagementControlDiagnostic;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublicProductUrlValidator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(
        name = "yaga.hiding.enabled",
        havingValue = "true"
)
public class PlaywrightYagaHidingBrowserAutomation
        implements YagaHidingBrowserAutomation {

    private static final Pattern MANAGEMENT_CONTROL_TEXT =
            Pattern.compile(
                    "Peida|Muuda toodet|M\\u00e4rgi m\\u00fc\\u00fcduks|Kustuta",
                    Pattern.CASE_INSENSITIVE
            );
    private static final String HIDE_BUTTON_TEXT = "Peida";
    private static final String EDIT_BUTTON_TEXT = "Muuda toodet";

    private final YagaHidingProperties properties;

    public PlaywrightYagaHidingBrowserAutomation(
            YagaHidingProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public YagaHidingPreparedBrowserSession prepareSession(
            YagaHidingDraftData draft
    ) {
        Path authStatePath =
                Paths.get(properties.getAuthStatePath())
                        .toAbsolutePath()
                        .normalize();

        if (!isUsableAuthStateFile(authStatePath)) {
            throw new YagaHidingAuthException(
                    "Yaga auth state file is missing",
                    authStateDiagnostics(authStatePath)
            );
        }

        try {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setChannel("chrome")
                            .setHeadless(properties.isHeadless())
                            .setSlowMo(properties.getSlowMoMs())
            );
            BrowserContext context = browser.newContext(
                    contextOptions(authStatePath)
            );
            Page page = context.newPage();
            String requestedManagementUrl = managementUrl(draft);
            if (!YagaPublicProductUrlValidator
                    .isExpectedPublicProductUrl(
                            requestedManagementUrl,
                            draft.shopSlug(),
                            draft.oldProductSlug()
                    )) {
                closeQuietly(context, browser, playwright);
                throw new YagaPublishingFormException(
                        "Yaga hide management URL is not valid"
                );
            }
            page.navigate(requestedManagementUrl);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            return new PlaywrightHidingSession(
                    draft,
                    playwright,
                    browser,
                    context,
                    page,
                    requestedManagementUrl,
                    authStatePath
            );

        } catch (YagaPublishingAuthException |
                 YagaHidingAuthException |
                 YagaPublishingFormException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new YagaPublishingFormException(
                    "Failed to inspect Yaga hide control",
                    exception
            );
        }
    }

    @Override
    public YagaHideControlInspection inspectHideControl(
            YagaHidingPreparedBrowserSession session
    ) {
        PlaywrightHidingSession typed = typedSession(session);
        return inspect(
                typed.page(),
                typed.draft(),
                typed.requestedManagementUrl(),
                typed.authStatePath()
        );
    }

    @Override
    public YagaHideResult hidePreparedSession(
            YagaHidingPreparedBrowserSession session
    ) {
        PlaywrightHidingSession typed = typedSession(session);
        HideControlResolution resolution = resolveHideControl(
                typed.page(),
                typed.draft(),
                typed.requestedManagementUrl(),
                typed.authStatePath()
        );
        if (!resolution.inspection().readyForConfirmation()) {
            throw new YagaPublishingFormException(
                    "Yaga hide button is not ready for confirmation"
            );
        }

        resolution.button().click();

        return new YagaHideResult(
                true,
                safeCurrentUrl(typed.page()),
                Instant.now()
        );
    }

    @Override
    public void closeSession(YagaHidingPreparedBrowserSession session) {
        if (session instanceof PlaywrightHidingSession typed) {
            closeQuietly(
                    typed.context(),
                    typed.browser(),
                    typed.playwright()
            );
        }
    }

    private PlaywrightHidingSession typedSession(
            YagaHidingPreparedBrowserSession session
    ) {
        if (session instanceof PlaywrightHidingSession typed) {
            return typed;
        }
        throw new IllegalArgumentException(
                "Unsupported Yaga hiding browser session"
        );
    }

    private String managementUrl(YagaHidingDraftData draft) {
        return properties.getManagementUrlTemplate()
                .replace("{shopSlug}", draft.shopSlug())
                .replace("{productSlug}", draft.oldProductSlug())
                .replace(
                        "{externalListingId}",
                        draft.oldExternalListingId()
                );
    }

    Browser.NewContextOptions contextOptions(Path authStatePath) {
        return new Browser.NewContextOptions()
                .setStorageStatePath(authStatePath)
                .setViewportSize(1440, 900);
    }

    YagaHideControlInspection inspect(
            Page page,
            YagaHidingDraftData draft,
            String requestedManagementUrl,
            Path authStatePath
    ) {
        return resolveHideControl(
                page,
                draft,
                requestedManagementUrl,
                authStatePath
        ).inspection();
    }

    private HideControlResolution resolveHideControl(
            Page page,
            YagaHidingDraftData draft,
            String requestedManagementUrl,
            Path authStatePath
    ) {
        List<Locator> candidates = hideCandidates(page);
        int candidateCount = 0;
        int visibleCandidateCount = 0;
        int enabledCandidateCount = 0;
        String text = null;
        String accessibleName = null;
        String tagName = null;
        String typeAttribute = null;

        for (Locator candidate : candidates) {
            candidateCount++;
            if (safeIsVisible(candidate)) {
                visibleCandidateCount++;
            }
            if (safeIsEnabled(candidate)) {
                enabledCandidateCount++;
            }
            if (candidateCount == 1) {
                text = normalizeWhitespace(safeText(candidate));
                accessibleName = safeAccessibleName(candidate);
                tagName = safeAttribute(candidate, "tagName");
                typeAttribute = safeAttribute(candidate, "type");
            } else {
                text = null;
                accessibleName = null;
                tagName = null;
                typeAttribute = null;
            }
        }

        String currentUrl = safeCurrentUrl(page);
        YagaHideTargetDiagnostics diagnostics =
                collectTargetDiagnostics(
                        page,
                        draft,
                        requestedManagementUrl,
                        authStatePath
                );
        int targetEvidenceCount =
                targetEvidenceCount(diagnostics);
        boolean safeType = typeAttribute == null ||
                typeAttribute.isBlank() ||
                "button".equalsIgnoreCase(typeAttribute) ||
                "submit".equalsIgnoreCase(typeAttribute);
        boolean targetMatches = targetEvidenceCount >= 2 &&
                diagnostics.editControlVisible() &&
                diagnostics.hideControlVisible() &&
                YagaPublicProductUrlValidator
                        .isExpectedPublicProductUrl(
                                currentUrl,
                                draft.shopSlug(),
                                draft.oldProductSlug()
                        );
        boolean ready =
                targetMatches &&
                        candidateCount == 1 &&
                        visibleCandidateCount == 1 &&
                        enabledCandidateCount == 1 &&
                        HIDE_BUTTON_TEXT.equals(text) &&
                        "button".equalsIgnoreCase(tagName) &&
                        safeType;

        if (!ready) {
            diagnostics = withFailureScreenshot(page, diagnostics);
        }

        YagaHideControlInspection inspection =
                new YagaHideControlInspection(
                        currentUrl,
                        draft.oldExternalListingId(),
                        draft.oldProductSlug(),
                        candidateCount,
                        visibleCandidateCount,
                        enabledCandidateCount,
                        text,
                        accessibleName,
                        tagName,
                        typeAttribute,
                        ready,
                        Instant.now(),
                        diagnostics
                );

        return new HideControlResolution(
                inspection,
                ready ? candidates.getFirst() : null
        );
    }

    private YagaHideTargetDiagnostics collectTargetDiagnostics(
            Page page,
            YagaHidingDraftData draft,
            String requestedManagementUrl,
            Path authStatePath
    ) {
        String currentUrl = safeCurrentUrl(page);
        String publicUrl = "https://www.yaga.ee/" +
                draft.shopSlug() +
                "/toode/" +
                draft.oldProductSlug();

        boolean editVisible = safeCount(page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName(EDIT_BUTTON_TEXT)
                        .setExact(true)
        )) > 0;
        boolean hideVisible = safeCount(page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions()
                        .setName(HIDE_BUTTON_TEXT)
                        .setExact(true)
        )) > 0;

        return new YagaHideTargetDiagnostics(
                draft.oldListingId(),
                draft.shopSlug(),
                draft.oldProductSlug(),
                draft.oldExternalListingId(),
                requestedManagementUrl,
                currentUrl,
                safeTitle(page),
                authStatePath.toString(),
                Files.exists(authStatePath),
                Files.isReadable(authStatePath),
                safeSize(authStatePath),
                domContains(page, draft.oldProductSlug()),
                domContains(page, draft.oldExternalListingId()),
                domContains(page, publicUrl),
                YagaPublicProductUrlValidator
                        .isExpectedPublicProductUrl(
                                currentUrl,
                                draft.shopSlug(),
                                draft.oldProductSlug()
                        ),
                editVisible,
                hideVisible,
                editVisible && hideVisible,
                null,
                managementControls(page)
        );
    }

    private int targetEvidenceCount(
            YagaHideTargetDiagnostics diagnostics
    ) {
        int count = 0;
        if (diagnostics.managementUrlContainsExpectedTarget()) {
            count++;
        }
        if (diagnostics.canonicalProductUrlInDom()) {
            count++;
        }
        if (diagnostics.expectedProductSlugInDom() ||
                diagnostics.expectedExternalListingIdInDom()) {
            count++;
        }
        return count;
    }

    private YagaHideTargetDiagnostics withFailureScreenshot(
            Page page,
            YagaHideTargetDiagnostics diagnostics
    ) {
        Path screenshotPath = null;
        try {
            screenshotPath = takeFailureScreenshot(page);
        } catch (RuntimeException ignored) {
        }

        if (screenshotPath == null) {
            return diagnostics;
        }

        return new YagaHideTargetDiagnostics(
                diagnostics.requestedOldListingId(),
                diagnostics.expectedShopSlug(),
                diagnostics.expectedProductSlug(),
                diagnostics.expectedExternalListingId(),
                diagnostics.requestedManagementUrl(),
                diagnostics.currentUrl(),
                diagnostics.pageTitle(),
                diagnostics.resolvedAuthStatePath(),
                diagnostics.authStateFileExists(),
                diagnostics.authStateFileReadable(),
                diagnostics.authStateFileSize(),
                diagnostics.expectedProductSlugInDom(),
                diagnostics.expectedExternalListingIdInDom(),
                diagnostics.canonicalProductUrlInDom(),
                diagnostics.managementUrlContainsExpectedTarget(),
                diagnostics.editControlVisible(),
                diagnostics.hideControlVisible(),
                diagnostics.ownerControlsVisible(),
                screenshotPath,
                diagnostics.managementControls()
        );
    }

    private boolean isUsableAuthStateFile(Path authStatePath) {
        try {
            return Files.exists(authStatePath) &&
                    Files.isRegularFile(authStatePath) &&
                    Files.isReadable(authStatePath) &&
                    Files.size(authStatePath) > 0;
        } catch (RuntimeException | java.io.IOException exception) {
            return false;
        }
    }

    private Map<String, String> authStateDiagnostics(
            Path authStatePath
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(
                "resolvedAuthStatePath",
                authStatePath.toString()
        );
        details.put(
                "authStateFileExists",
                Boolean.toString(Files.exists(authStatePath))
        );
        details.put(
                "authStateFileRegular",
                Boolean.toString(Files.isRegularFile(authStatePath))
        );
        details.put(
                "authStateFileReadable",
                Boolean.toString(Files.isReadable(authStatePath))
        );
        details.put(
                "authStateFileSize",
                Long.toString(safeSize(authStatePath))
        );
        return details;
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (RuntimeException | java.io.IOException exception) {
            return 0L;
        }
    }

    private List<Locator> hideCandidates(Page page) {
        return safeAll(
                page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions()
                                .setName(HIDE_BUTTON_TEXT)
                                .setExact(true)
                )
        );
    }

    private List<YagaManagementControlDiagnostic> managementControls(
            Page page
    ) {
        List<YagaManagementControlDiagnostic> controls =
                new ArrayList<>();
        Locator candidates =
                page.locator("button, a, [role='button'], input[type='submit']")
                        .filter(new Locator.FilterOptions()
                                .setHasText(MANAGEMENT_CONTROL_TEXT));

        for (Locator candidate : safeAll(candidates)) {
            if (!safeIsVisible(candidate)) {
                continue;
            }
            controls.add(new YagaManagementControlDiagnostic(
                    safeAttribute(candidate, "tagName"),
                    safeAttribute(candidate, "href"),
                    safeAccessibleName(candidate)
            ));
            if (controls.size() >= 20) {
                break;
            }
        }

        return List.copyOf(controls);
    }

    private Path takeFailureScreenshot(Page page) {
        try {
            Path screenshotsDirectory =
                    Paths.get("..", "playwright", "screenshots")
                            .toAbsolutePath()
                            .normalize();
            Files.createDirectories(screenshotsDirectory);
            Path screenshotPath = screenshotsDirectory.resolve(
                    "yaga-hide-preparation-failure-" +
                            System.currentTimeMillis() +
                            ".png"
            );
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath)
                    .setFullPage(true));
            return screenshotPath;
        } catch (Exception exception) {
            return null;
        }
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

    private List<Locator> safeAll(Locator locator) {
        try {
            return locator.all();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private boolean safeIsVisible(Locator locator) {
        try {
            return locator.isVisible();
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

    private int safeCount(Locator locator) {
        try {
            return locator.count();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String safeText(Locator locator) {
        try {
            String value = locator.textContent();
            return value == null ? "" : value;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String safeAccessibleName(Locator locator) {
        try {
            Object value = locator.evaluate("""
                    element => element.getAttribute('aria-label') ||
                        element.getAttribute('title') ||
                        element.textContent
                    """);
            return value == null
                    ? null
                    : normalizeWhitespace(value.toString());
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

    private boolean domContains(Page page, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        try {
            Object value = page.locator("body").evaluate(
                    "(element, expected) => " +
                            "element.textContent.includes(expected) || " +
                            "document.documentElement.outerHTML.includes(expected)",
                    expected
            );
            return Boolean.TRUE.equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
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

    private String safeCurrentUrl(Page page) {
        try {
            return page.url();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalizeWhitespace(String value) {
        return value == null
                ? ""
                : value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record PlaywrightHidingSession(
            YagaHidingDraftData draft,
            Playwright playwright,
            Browser browser,
            BrowserContext context,
            Page page,
            String requestedManagementUrl,
            Path authStatePath
    ) implements YagaHidingPreparedBrowserSession {
    }

    private record HideControlResolution(
            YagaHideControlInspection inspection,
            Locator button
    ) {
    }
}
