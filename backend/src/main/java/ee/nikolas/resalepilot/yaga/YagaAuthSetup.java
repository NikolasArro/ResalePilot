package ee.nikolas.resalepilot.yaga;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YagaAuthSetup {

    private static final Path AUTH_DIRECTORY =
            Paths.get("playwright", ".auth");

    private static final Path AUTH_STATE_PATH =
            AUTH_DIRECTORY.resolve("yaga-state.json");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(AUTH_DIRECTORY);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .connectOverCDP("http://127.0.0.1:9222");

            if (browser.contexts().isEmpty()) {
                throw new IllegalStateException(
                        "Не найден открытый контекст Chrome"
                );
            }

            BrowserContext context =
                    browser.contexts().getFirst();

            context.storageState(
                    new BrowserContext.StorageStateOptions()
                            .setPath(AUTH_STATE_PATH)
            );

            System.out.println(
                    "Авторизация Yaga сохранена: " +
                            AUTH_STATE_PATH.toAbsolutePath()
            );
        }
    }
}