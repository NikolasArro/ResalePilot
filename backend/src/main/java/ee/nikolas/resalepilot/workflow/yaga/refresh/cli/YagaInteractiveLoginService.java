package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountAuthStateResolver;
import ee.nikolas.resalepilot.workflow.yaga.auth.YagaPlaywrightAuthState;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Service
public class YagaInteractiveLoginService {

    private final YagaPublishingProperties publishingProperties;

    public YagaInteractiveLoginService(
            YagaPublishingProperties publishingProperties
    ) {
        this.publishingProperties = publishingProperties;
    }

    public Path refreshAuthState(
            YagaAccount account,
            String cdpUrl
    ) throws IOException {
        Path authStatePath = YagaAccountAuthStateResolver.resolve(
                account,
                publishingProperties.getAuthStatePath()
        );
        Path authDirectory = authStatePath.getParent();
        if (authDirectory != null) {
            Files.createDirectories(authDirectory);
        }

        printChromeCommand(account, cdpUrl);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP(cdpUrl);
            BrowserContext context = context(browser);
            Page page = page(context);

            page.navigate("https://www.yaga.ee/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            System.out.println(
                    "Log in to Yaga as shop '" + account.getShopSlug() +
                            "', then press ENTER here."
            );
            new Scanner(System.in).nextLine();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            verifyShop(account, page);

            context.storageState(
                    YagaPlaywrightAuthState.storageStateOptions(
                            authStatePath
                    )
            );
            YagaPlaywrightAuthState.saveSessionStorage(page, authStatePath);
            System.out.println(
                    "Saved Yaga auth state for account " + account.getId() +
                            " at " + authStatePath.toAbsolutePath()
            );
            return authStatePath;
        }
    }

    private void verifyShop(YagaAccount account, Page page) {
        List<String> visibleUrls = new ArrayList<>();
        visibleUrls.add(page.url());
        @SuppressWarnings("unchecked")
        List<String> hrefs = (List<String>) page.evaluate(
                "() => Array.from(document.querySelectorAll('a[href]'))" +
                        ".map(anchor => anchor.href)"
        );
        visibleUrls.addAll(hrefs);

        YagaShopIdentityVerifier.ShopIdentityResult result =
                YagaShopIdentityVerifier.verify(
                        account.getShopSlug(),
                        visibleUrls
                );
        if (!result.accepted()) {
            throw new YagaRefreshRequestInvalidException(
                    result.safeMessage()
            );
        }
        System.out.println(
                "Verified active Yaga shop: " + result.verifiedShopSlug()
        );
    }

    private BrowserContext context(Browser browser) {
        if (browser.contexts().isEmpty()) {
            throw new YagaRefreshRequestInvalidException(
                    "No Chrome context found. Start Chrome with remote debugging first."
            );
        }
        return browser.contexts().getFirst();
    }

    private Page page(BrowserContext context) {
        if (!context.pages().isEmpty()) {
            return context.pages().getFirst();
        }
        return context.newPage();
    }

    private void printChromeCommand(YagaAccount account, String cdpUrl) {
        String port = cdpUrl.substring(cdpUrl.lastIndexOf(':') + 1);
        System.out.println("Start account-specific Chrome if needed:");
        System.out.println(
                "chrome.exe --remote-debugging-port=" + port +
                        " --user-data-dir=\"%TEMP%\\resalepilot-yaga-" +
                        account.getId() + "\""
        );
    }
}
