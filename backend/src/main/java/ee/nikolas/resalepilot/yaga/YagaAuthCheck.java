package ee.nikolas.resalepilot.yaga;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YagaAuthCheck {

    private static final String YAGA_URL =
            "https://www.yaga.ee/";

    private static final Path AUTH_STATE_PATH =
            Paths.get(
                    "playwright",
                    ".auth",
                    "yaga-state.json"
            );

    public static void main(String[] args) {
        if (Files.notExists(AUTH_STATE_PATH)) {
            throw new IllegalStateException(
                    "Файл авторизации не найден: " +
                            AUTH_STATE_PATH.toAbsolutePath()
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
                    new Browser.NewContextOptions()
                            .setStorageStatePath(AUTH_STATE_PATH)
                            .setViewportSize(1440, 900)
            );

            Page page = context.newPage();
            page.navigate(YAGA_URL);

            page.waitForTimeout(3000);

            System.out.println(
                    "Открытая страница: " + page.url()
            );
            System.out.println(
                    "Заголовок: " + page.title()
            );
            System.out.println();
            System.out.println(
                    "Проверь в браузере, что аккаунт Yaga авторизован."
            );
            System.out.println(
                    "Нажми Enter в консоли для завершения."
            );

            System.in.read();

            context.close();
            browser.close();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Не удалось проверить авторизацию Yaga",
                    exception
            );
        }
    }
}