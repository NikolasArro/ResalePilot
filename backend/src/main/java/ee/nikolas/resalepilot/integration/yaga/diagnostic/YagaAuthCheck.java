package ee.nikolas.resalepilot.integration.yaga.diagnostic;

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

    private static final Path AUTH_STATE_PATH =
            Paths.get(
                    "playwright",
                    ".auth",
                    "yaga-state.json"
            );

    public static void main(String[] args) {
        Path authStatePath = authStatePath(args);
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
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(3000);

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

    private static Path authStatePath(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if ("--auth-state-path".equals(args[index]) &&
                    index + 1 < args.length) {
                return Paths.get(args[++index])
                        .toAbsolutePath()
                        .normalize();
            }
        }
        return AUTH_STATE_PATH.toAbsolutePath().normalize();
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

    private static void printLiveStorageSummary(Page page) {
        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);
        System.out.println("Fresh context live storage summary:");
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
