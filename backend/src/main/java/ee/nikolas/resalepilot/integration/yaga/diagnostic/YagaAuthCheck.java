package ee.nikolas.resalepilot.integration.yaga.diagnostic;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountAuthStateResolver;
import ee.nikolas.resalepilot.workflow.yaga.auth.YagaPlaywrightAuthState;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YagaAuthCheck {

    private static final String YAGA_URL =
            "https://www.yaga.ee/muuk/lisa-toode";

    public static void main(String[] args) {
        Path authStatePath = resolveAuthStatePath(args);
        printSavedStateSummary(authStatePath);

        if (Files.notExists(authStatePath)) {
            throw new IllegalStateException(
                    "Yaga auth state file was not found: " +
                            authStatePath.toAbsolutePath()
            );
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setChannel("chrome")
                            .setHeadless(false)
                            .setSlowMo(100)
            );

            BrowserContext context = browser.newContext(
                    YagaPlaywrightAuthState.contextOptions(authStatePath)
            );
            YagaPlaywrightAuthState.restoreSessionStorage(
                    context,
                    authStatePath
            );

            Page page = context.newPage();
            page.navigate(YAGA_URL);
            waitForYagaDocumentBeforeDiagnostics(page);

            printLiveStorageSummary(page);

            System.out.println("Opened page: " + page.url());
            System.out.println("Title: " + page.title());
            System.out.println();
            System.out.println(
                    "Check in the browser whether the Yaga account is authenticated."
            );
            System.out.println("Press Enter in the console to exit.");

            System.in.read();

            context.close();
            browser.close();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to check Yaga authentication",
                    exception
            );
        }
    }

    static Path resolveAuthStatePath(String[] args) {
        Long accountId = null;
        Path explicitPath = null;
        for (int index = 0; index < args.length; index++) {
            if ("--account-id".equals(args[index]) &&
                    index + 1 < args.length) {
                accountId = Long.parseLong(args[++index]);
                continue;
            }
            if ("--auth-state-path".equals(args[index]) &&
                    index + 1 < args.length) {
                explicitPath = Paths.get(args[++index]);
            }
        }
        return YagaAccountAuthStateResolver.resolveDiagnostic(
                accountId,
                explicitPath
        );
    }

    private static void printSavedStateSummary(Path authStatePath) {
        YagaPlaywrightAuthState.AuthStateSummary summary =
                YagaPlaywrightAuthState.summarize(authStatePath);
        System.out.println("Yaga auth-state diagnostic:");
        System.out.println(
                "- resolved auth-state path: " +
                        summary.resolvedAuthStatePath()
        );
        System.out.println("- file exists: " + summary.fileExists());
        System.out.println("- file size: " + summary.fileSize());
        System.out.println(
                "- file modified at: " + summary.fileModifiedAt()
        );
        System.out.println(
                "- saved cookie count: " + summary.savedCookieCount()
        );
        System.out.println(
                "- saved cookie metadata: " +
                        summary.savedCookieMetadata()
        );
        System.out.println("- saved origins: " + summary.savedOrigins());
        System.out.println(
                "- IndexedDB entries present: " +
                        summary.indexedDbEntriesPresent()
        );
        System.out.println(
                "- sessionStorage restoration configured: " +
                        summary.sessionStorageConfigured()
        );
        System.out.println(
                "- sessionStorage key names: " +
                        summary.sessionStorageKeyNames()
        );
    }

    static void waitForYagaDocumentBeforeDiagnostics(Page page) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForURL(
                    "**://*.yaga.ee/**",
                    new Page.WaitForURLOptions().setTimeout(5000)
            );
        } catch (RuntimeException exception) {
            System.out.println(
                    "Fresh context live storage summary wait skipped: " +
                            "target Yaga document was not stable before diagnostics"
            );
        }
        try {
            page.waitForTimeout(3000);
        } catch (RuntimeException exception) {
            System.out.println(
                    "Fresh context live storage summary delay skipped: " +
                            "page was not stable before diagnostics"
            );
        }
    }

    static void printLiveStorageSummary(Page page) {
        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);
        System.out.println("Fresh context live storage summary:");
        if (!summary.storageAvailable()) {
            System.out.println("- page URL: " + summary.pageUrl());
            System.out.println(
                    "- storage unavailable: " +
                            summary.unavailableReason()
            );
            return;
        }
        System.out.println("- origin: " + summary.origin());
        System.out.println(
                "- localStorage keys: " + summary.localStorageKeys()
        );
        System.out.println(
                "- sessionStorage keys: " + summary.sessionStorageKeys()
        );
        System.out.println(
                "- IndexedDB databases: " + summary.indexedDbNames()
        );
    }
}
