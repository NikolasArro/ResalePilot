package ee.nikolas.resalepilot.integration.yaga.diagnostic;

import ee.nikolas.resalepilot.workflow.yaga.auth.YagaPlaywrightAuthState;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class YagaAuthSetup {

    private static final Path AUTH_DIRECTORY =
            Paths.get("playwright", ".auth");

    private static final Path AUTH_STATE_PATH =
            AUTH_DIRECTORY.resolve("yaga-state.json");
    private static final String DEFAULT_CDP_URL =
            "http://127.0.0.1:9333";

    public static void main(String[] args) throws IOException {
        Path authStatePath = authStatePath(args);
        String cdpUrl = cdpUrl(args);
        Path authDirectory = authStatePath.getParent();
        if (authDirectory != null) {
            Files.createDirectories(authDirectory);
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .connectOverCDP(cdpUrl);
            System.out.println(
                    "Connected to Chrome over CDP: " + cdpUrl
            );

            if (browser.contexts().isEmpty()) {
                throw new IllegalStateException(
                        "No open Chrome context found. Start Chrome with remote debugging first."
                );
            }

            BrowserContext context =
                    authenticatedYagaContext(browser.contexts());
            Page page = authenticatedYagaPage(context);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            context.storageState(
                    YagaPlaywrightAuthState.storageStateOptions(
                            authStatePath
                    )
            );
            YagaPlaywrightAuthState.saveSessionStorage(
                    page,
                    authStatePath
            );

            System.out.println(
                    "Yaga auth state saved for account-specific use: " +
                            authStatePath.toAbsolutePath()
            );
            printLiveStorageSummary(page);
            printSavedStateSummary(authStatePath);
        }
    }

    private static BrowserContext authenticatedYagaContext(
            List<BrowserContext> contexts
    ) {
        for (BrowserContext context : contexts) {
            for (Page page : context.pages()) {
                if (isYagaUrl(page.url())) {
                    return context;
                }
            }
        }

        System.out.println(
                "Open CDP contexts/pages, safe URL summary:"
        );
        for (int contextIndex = 0; contextIndex < contexts.size();
             contextIndex++) {
            BrowserContext context = contexts.get(contextIndex);
            System.out.println(
                    "context[" + contextIndex + "] pageCount=" +
                            context.pages().size()
            );
            for (Page page : context.pages()) {
                System.out.println("- " + safeUrl(page.url()));
            }
        }

        throw new IllegalStateException(
                "No open authenticated Yaga page found in the Chrome CDP contexts. " +
                        "Open https://www.yaga.ee/muuk/lisa-toode in the debug Chrome first."
        );
    }

    private static Page authenticatedYagaPage(BrowserContext context) {
        for (Page page : context.pages()) {
            if (isYagaUrl(page.url())) {
                return page;
            }
        }
        throw new IllegalStateException(
                "Selected Chrome context does not contain a Yaga page"
        );
    }

    private static boolean isYagaUrl(String url) {
        String host = host(url);
        return "yaga.ee".equals(host) || "www.yaga.ee".equals(host);
    }

    private static String safeUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (URISyntaxException exception) {
            return "unparseable-url";
        }
    }

    private static String host(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static void printLiveStorageSummary(Page page) {
        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);
        System.out.println("Live Yaga storage summary:");
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

    private static void printSavedStateSummary(Path authStatePath) {
        YagaPlaywrightAuthState.AuthStateSummary summary =
                YagaPlaywrightAuthState.summarize(authStatePath);
        System.out.println("Saved Yaga auth-state summary:");
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

    private static Path authStatePath(String[] args) {
        Long accountId = null;
        Path explicitPath = null;
        for (int index = 0; index < args.length; index++) {
            if ("--account-id".equals(args[index]) && index + 1 < args.length) {
                accountId = Long.parseLong(args[++index]);
                continue;
            }
            if ("--auth-state-path".equals(args[index]) &&
                    index + 1 < args.length) {
                explicitPath = Paths.get(args[++index]);
            }
        }

        if (explicitPath != null) {
            return explicitPath.toAbsolutePath().normalize();
        }
        if (accountId != null) {
            return AUTH_DIRECTORY
                    .resolve("yaga-account-" + accountId + "-state.json")
                    .toAbsolutePath()
                    .normalize();
        }
        return AUTH_STATE_PATH.toAbsolutePath().normalize();
    }

    private static String cdpUrl(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if ("--cdp-url".equals(args[index]) && index + 1 < args.length) {
                return args[++index];
            }
        }
        return DEFAULT_CDP_URL;
    }
}
