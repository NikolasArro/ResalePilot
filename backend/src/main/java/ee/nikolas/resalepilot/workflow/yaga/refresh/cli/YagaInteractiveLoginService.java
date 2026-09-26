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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;

@Service
public class YagaInteractiveLoginService {

    private static final String YAGA_HOME_URL = "https://www.yaga.ee/";
    private static final String YAGA_AUTHENTICATED_IDENTITY_URL =
            "https://www.yaga.ee/muuk/lisa-toode";

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
            Page page = context.newPage();

            page.navigate(YAGA_HOME_URL);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            System.out.println(
                    "Log in to Yaga as shop '" + account.getShopSlug() +
                            "', then press ENTER here."
            );
            new Scanner(System.in).nextLine();

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.navigate(YAGA_AUTHENTICATED_IDENTITY_URL);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            verifyShop(account, context, page);

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

    private void verifyShop(
            YagaAccount account,
            BrowserContext context,
            Page page
    ) {
        AuthenticatedShopSignal signal = authenticatedShopSignal(page);

        YagaShopIdentityVerifier.ShopIdentityResult result =
                YagaShopIdentityVerifier.verifyAuthenticatedShop(
                        account.getShopSlug(),
                        signal.shopSlug(),
                        signal.source(),
                        signal.failureReason()
                );
        if (!result.accepted()) {
            System.out.println(
                    "Yaga shop verification failed: inspectedPageCount=" +
                            context.pages().size() +
                            " selectedVerificationPage=" +
                            safeUrl(page.url()) +
                            " expectedShopSlug=" + account.getShopSlug() +
                            " detectedShopSlug=" +
                            valueOrUnknown(signal.shopSlug()) +
                            " verificationSource=" +
                            valueOrUnknown(signal.source()) +
                            " failureReason=" +
                            valueOrUnknown(result.failureReason())
            );
            throw new YagaRefreshRequestInvalidException(
                    result.safeMessage()
            );
        }
        System.out.println(
                "Verified active Yaga shop: " + result.verifiedShopSlug() +
                        " via " + result.verificationSource() +
                        " inspectedPageCount=" + context.pages().size() +
                        " selectedVerificationPage=" + safeUrl(page.url())
        );
    }

    private AuthenticatedShopSignal authenticatedShopSignal(Page page) {
        @SuppressWarnings("unchecked")
        Map<String, String> value = (Map<String, String>) page.evaluate(
                """
                        () => {
                          const safeSlug = value => {
                            if (!value || typeof value !== 'string') return null;
                            const normalized = value.toLowerCase();
                            return /^[a-z0-9][a-z0-9-]{1,149}$/.test(normalized)
                              ? normalized
                              : null;
                          };
                          const reserved = new Set([
                            '_next', 'api', 'auth', 'blog', 'cart', 'checkout',
                            'en', 'et', 'help', 'konto', 'login', 'logi-sisse',
                            'logout', 'muuk', 'ostukorv', 'pood', 'profile',
                            'profiil', 'registreeri', 'ru', 'search', 'seaded',
                            'settings', 'toode'
                          ]);
                          const slugFromUrl = href => {
                            try {
                              const url = new URL(href, window.location.href);
                              if (url.hostname !== 'yaga.ee' &&
                                  url.hostname !== 'www.yaga.ee') return null;
                              const parts = url.pathname.split('/').filter(Boolean)
                                .map(part => part.toLowerCase());
                              const pood = parts.indexOf('pood');
                              if (pood >= 0 && parts.length > pood + 1) {
                                return safeSlug(parts[pood + 1]);
                              }
                              if (parts.length === 1 && !reserved.has(parts[0])) {
                                return safeSlug(parts[0]);
                              }
                              return null;
                            } catch {
                              return null;
                            }
                          };
                          const unique = values => Array.from(new Set(values.filter(Boolean)));
                          const links = Array.from(document.querySelectorAll(
                            'header a[href], nav a[href], [role="banner"] a[href], ' +
                            '[role="navigation"] a[href], [role="menu"] a[href], ' +
                            '[data-testid*="account" i] a[href], ' +
                            '[data-testid*="profile" i] a[href], ' +
                            '[data-testid*="shop" i] a[href]'
                          ));
                          const linkSlugs = unique(links.map(link => slugFromUrl(link.href)));
                          if (linkSlugs.length === 1) {
                            return {
                              shopSlug: linkSlugs[0],
                              source: 'AUTHENTICATED_UI_SHOP_LINK',
                              failureReason: null
                            };
                          }
                          if (linkSlugs.length > 1) {
                            return {
                              shopSlug: linkSlugs[0],
                              source: 'AUTHENTICATED_UI_SHOP_LINK',
                              failureReason: 'MULTIPLE_AUTHENTICATED_SHOP_LINKS'
                            };
                          }

                          const script = document.querySelector('script#__NEXT_DATA__');
                          if (script && script.textContent) {
                            try {
                              const data = JSON.parse(script.textContent);
                              const slugs = [];
                              const visit = (node, path) => {
                                if (!node || typeof node !== 'object') return;
                                if (Array.isArray(node)) {
                                  node.slice(0, 50).forEach((item, index) =>
                                    visit(item, path.concat(String(index))));
                                  return;
                                }
                                const pathText = path.join('.').toLowerCase();
                                const authenticatedPath =
                                  /(^|\\.)(me|auth|account|profile|user|currentuser|current_user|viewer|shop)(\\.|$)/i
                                    .test(pathText);
                                for (const [key, child] of Object.entries(node)) {
                                  if (authenticatedPath &&
                                      typeof child === 'string' &&
                                      /^(shopslug|shop_slug|slug|username|user_slug)$/i
                                        .test(key)) {
                                    const slug = safeSlug(child);
                                    if (slug) slugs.push(slug);
                                  }
                                  visit(child, path.concat(key));
                                }
                              };
                              visit(data, []);
                              const nextSlugs = unique(slugs);
                              if (nextSlugs.length === 1) {
                                return {
                                  shopSlug: nextSlugs[0],
                                  source: 'AUTHENTICATED_NEXT_DATA',
                                  failureReason: null
                                };
                              }
                              if (nextSlugs.length > 1) {
                                return {
                                  shopSlug: nextSlugs[0],
                                  source: 'AUTHENTICATED_NEXT_DATA',
                                  failureReason: 'MULTIPLE_AUTHENTICATED_NEXT_DATA_SLUGS'
                                };
                              }
                            } catch {
                              return {
                                shopSlug: null,
                                source: 'AUTHENTICATED_NEXT_DATA',
                                failureReason: 'NEXT_DATA_UNREADABLE'
                              };
                            }
                          }
                          return {
                            shopSlug: null,
                            source: 'AUTHENTICATED_IDENTITY',
                            failureReason: 'NO_AUTHENTICATED_SHOP_SIGNAL_FOUND'
                          };
                        }
                        """
        );
        return new AuthenticatedShopSignal(
                value.get("shopSlug"),
                value.get("source"),
                value.get("failureReason")
        );
    }

    private String safeUrl(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || host.isBlank()) {
                return "unknown";
            }
            return host + (path == null || path.isBlank() ? "/" : path);
        } catch (URISyntaxException exception) {
            return "unparseable";
        }
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record AuthenticatedShopSignal(
            String shopSlug,
            String source,
            String failureReason
    ) {
    }

    private BrowserContext context(Browser browser) {
        if (browser.contexts().isEmpty()) {
            throw new YagaRefreshRequestInvalidException(
                    "No Chrome context found. Start Chrome with remote debugging first."
            );
        }
        return browser.contexts().getFirst();
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
