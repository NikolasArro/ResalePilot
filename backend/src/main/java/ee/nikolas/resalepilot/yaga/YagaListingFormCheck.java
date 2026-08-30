package ee.nikolas.resalepilot.yaga;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class YagaListingFormCheck {

    private static final String YAGA_URL =
            "https://www.yaga.ee/";

    private static final Path AUTH_STATE_PATH =
            Paths.get(
                    "playwright",
                    ".auth",
                    "yaga-state.json"
            );

    private static final Pattern SELL_BUTTON_TEXT =
            Pattern.compile(
                    "Lisa toode|Alusta müümist",
                    Pattern.CASE_INSENSITIVE
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
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            Locator sellLinks = page
                    .getByText(SELL_BUTTON_TEXT);

            if (sellLinks.count() == 0) {
                printAvailableActions(page);

                throw new IllegalStateException(
                        "Не найдена кнопка добавления товара"
                );
            }

            System.out.println(
                    "Найдено подходящих элементов: " +
                            sellLinks.count()
            );

            sellLinks.first().click();
            page.waitForTimeout(3000);

            System.out.println(
                    "Открытая страница: " + page.url()
            );
            System.out.println(
                    "Заголовок: " + page.title()
            );

            System.out.println(
                    "Форма открыта: " + page.url()
            );
            System.out.println(
                    "Используй Pick Locator в Playwright Inspector."
            );

            selectCategory(
                    page,
                    "Meestele",
                    "Püksid meestele",
                    "Teksapüksid meestele"
            );

            selectCondition(
                    page,
                    "Uus"
            );

            System.out.println(
                    "Категория и состояние выбраны."
            );

            page.pause();

            context.close();
            browser.close();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Не удалось открыть форму создания объявления",
                    exception
            );
        }
    }

    private static void printFormFields(Page page) {
        Locator fields = page.locator(
                "input, textarea, select"
        );

        System.out.println();
        System.out.println(
                "Найдено полей формы: " + fields.count()
        );

        for (int index = 0; index < fields.count(); index++) {
            Locator field = fields.nth(index);

            System.out.printf(
                    "%d. tag=%s, type=%s, name=%s, " +
                            "placeholder=%s, aria-label=%s%n",
                    index + 1,
                    field.evaluate(
                            "element => element.tagName.toLowerCase()"
                    ),
                    field.getAttribute("type"),
                    field.getAttribute("name"),
                    field.getAttribute("placeholder"),
                    field.getAttribute("aria-label")
            );
        }
    }

    private static void printAvailableActions(Page page) {
        Locator actions = page.locator(
                "a:visible, button:visible"
        );

        System.out.println(
                "Видимые ссылки и кнопки:"
        );

        int limit = Math.min(actions.count(), 50);

        for (int index = 0; index < limit; index++) {
            String text = actions
                    .nth(index)
                    .innerText()
                    .trim();

            if (!text.isBlank()) {
                System.out.println("- " + text);
            }
        }
    }

    private static void selectCategory(
            Page page,
            String... categoryPath
    ) {
        if (categoryPath.length == 0) {
            throw new IllegalArgumentException(
                    "Category path cannot be empty"
            );
        }

        openDropdown(
                page,
                "Vali kategooria"
        );

        selectVisibleOption(
                page,
                categoryPath[0]
        );

        for (int index = 1; index < categoryPath.length; index++) {
            openLastSubcategoryDropdown(page);

            selectVisibleOption(
                    page,
                    categoryPath[index]
            );
        }
    }

    private static void selectCondition(
            Page page,
            String condition
    ) {
        Locator dropdown = page.getByText(
                "Vali seisukord",
                new Page.GetByTextOptions()
                        .setExact(true)
        ).last();

        dropdown.click();

        /*
         * Всегда устанавливаем активный выбор
         * на первый пункт — Uus.
         */
        page.keyboard().press("Home");

        int arrowDownCount = switch (condition) {
            case "Uus" -> 0;
            case "Uueväärne" -> 1;
            case "Hea" -> 2;
            case "Keskmine" -> 3;
            default -> throw new IllegalArgumentException(
                    "Unsupported Yaga condition: " + condition
            );
        };

        for (int index = 0; index < arrowDownCount; index++) {
            page.keyboard().press("ArrowDown");
        }

        page.keyboard().press("Enter");
    }

    private static void openDropdown(
            Page page,
            String placeholder
    ) {
        page.getByText(
                        placeholder,
                        new Page.GetByTextOptions()
                                .setExact(true)
                )
                .last()
                .click();
    }

    private static void openLastSubcategoryDropdown(
            Page page
    ) {
        Locator dropdown = page.getByText(
                "Vali alamkategooria",
                new Page.GetByTextOptions()
                        .setExact(true)
        );

        if (dropdown.count() == 0) {
            throw new IllegalStateException(
                    "Yaga did not show the next subcategory field"
            );
        }

        dropdown.last().click();
    }

    private static void selectVisibleOption(
            Page page,
            String option
    ) {
        Locator matchingOptions = page.getByText(
                option,
                new Page.GetByTextOptions()
                        .setExact(true)
        );

        if (matchingOptions.count() == 0) {
            throw new IllegalStateException(
                    "Option not found: " + option
            );
        }

        matchingOptions.last().click();
    }
}