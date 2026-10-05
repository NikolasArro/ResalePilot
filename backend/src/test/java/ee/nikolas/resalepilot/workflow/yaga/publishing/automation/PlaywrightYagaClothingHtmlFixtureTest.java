package ee.nikolas.resalepilot.workflow.yaga.publishing.automation;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PlaywrightYagaClothingHtmlFixtureTest {

    private final PlaywrightYagaBrowserAutomation automation =
            new PlaywrightYagaBrowserAutomation(new YagaPublishingProperties());

    @Test
    void selectsExactSingleAndMultipleLabelsAndConfirmsEveryValue() {
        withPage(clothingForm(), page -> {
            automation.selectClothingFields(page, draft(
                    "S/M", "New Look", "Punane, Valge", "Vill, Teksa"));

            assertThat(page.locator("[data-field=size]").innerText()).isEqualTo("S/M");
            assertThat(page.locator("[data-field=brand]").innerText()).isEqualTo("New Look");
            assertThat(page.locator("[data-field=color]").innerText())
                    .isEqualTo("Punane, Valge");
            assertThat(page.locator("[data-field=material]").innerText())
                    .isEqualTo("Vill, Teksa");
            assertThat(page.locator("#unrelated-option").getAttribute("data-clicked"))
                    .isNull();
            assertThat(page.locator("[role='listbox']:visible").count()).isZero();
            assertThat(page.locator("#color-list").getAttribute("data-closed"))
                    .isEqualTo("escape");
            assertThat(page.locator("#material-list").getAttribute("data-closed"))
                    .isEqualTo("escape");
        });
    }

    @Test
    void closesOpenColorDropdownBeforeContinuingAndRetainsSelections() {
        withPage(clothingForm(), page -> {
            automation.selectClothingFields(page,
                    draft(null, null, "Punane, Valge", null));

            assertThat(page.locator("[data-field=color]").innerText())
                    .isEqualTo("Punane, Valge");
            assertThat(page.locator("#color-list").getAttribute("data-closed"))
                    .isEqualTo("escape");
            assertThat(page.locator("[role='listbox']:visible").count()).isZero();

            page.locator("input[placeholder='0']").fill("17");
            assertThat(page.locator("input[placeholder='0']").inputValue())
                    .isEqualTo("17");
        });
    }

    @Test
    void failsPreparationWhenMultiSelectDropdownDoesNotClose() {
        withPage(clothingForm().replace(
                "listbox.style.display = 'none';",
                "void(0);"
        ), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(page,
                            draft(null, null, "Punane", null))
            );
            assertThat(failure).hasMessageContaining(
                    "Värv options did not close");
            assertThat(failure.getDiagnostics().clothingSelection().field())
                    .isEqualTo("color");
            assertThat(page.locator("[data-field=color]").innerText())
                    .isEqualTo("Punane");
            assertThat(page.locator("[role='listbox']:visible").count())
                    .isEqualTo(1);
        });
    }

    @Test
    void selectsBrandThroughCategoryDependentAutocomplete() {
        withPage(liveBrandForm(), page -> {
            YagaPublishingFormException beforeCategory = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft(null, "Muu", null, null))
            );
            assertThat(beforeCategory.getDiagnostics().clothingSelection()
                    .controlFound()).isTrue();
            assertThat(beforeCategory.getDiagnostics().clothingSelection()
                    .controlVisible()).isFalse();

            page.locator("#select-category").click();
            automation.selectClothingFields(
                    page, draft(null, "Muu", null, null));
            assertThat(page.locator("#brand-input").inputValue())
                    .isEqualTo("Muu");
            assertThat(page.locator("#brand-input").getAttribute("aria-expanded"))
                    .isEqualTo("false");
        });
    }

    @Test
    void rejectsNonExactAutocompleteBrandOption() {
        withPage(liveBrandForm().replace("<span>Muu</span>",
                "<span>Muusik</span>"), page -> {
            page.locator("#select-category").click();
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft(null, "Muu", null, null))
            );
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("brand");
            assertThat(detail.controlFound()).isTrue();
            assertThat(detail.controlVisible()).isTrue();
            assertThat(detail.controlEnabled()).isTrue();
            assertThat(detail.exactOptionMatchCount()).isZero();
            assertThat(detail.visibleOptionLabelExamples()).contains("Muusik");
        });
    }

    @Test
    void skipsAbsentValuesWhenCategoryDoesNotRenderClothingControls() {
        withPage("<main><p>Raamatud</p></main>", page ->
                automation.selectClothingFields(page,
                        draft(null, null, null, null)));
        withPage(clothingForm(), page -> {
            automation.selectClothingFields(page, draft(null, " ", null, ""));
            assertThat(page.locator("[data-field=size]").innerText())
                    .isEqualTo("Vali suurus");
        });
    }

    @Test
    void refusesToDropSavedValueWhenCategoryHasNoMatchingControl() {
        withPage("<main><p>Raamatud</p></main>", page ->
                assertThatThrownBy(() -> automation.selectClothingFields(
                        page, draft("S/M", null, null, null)))
                        .isInstanceOf(YagaPublishingFormException.class)
                        .hasMessageContaining("Suurus control is unavailable"));
    }

    @Test
    void rejectsMissingExactOptionWithoutSelectingSimilarLabel() {
        withPage(clothingForm(), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft(null, "New Loo", null, null))
            );
            assertThat(failure).hasMessageContaining(
                    "Bränd option is not uniquely available: New Loo");
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("brand");
            assertThat(detail.requestedValues()).containsExactly("New Loo");
            assertThat(detail.controlFound()).isTrue();
            assertThat(detail.controlVisible()).isTrue();
            assertThat(detail.controlEnabled()).isTrue();
            assertThat(detail.exactOptionMatchCount()).isZero();
            assertThat(detail.selectedValuesObserved()).isEmpty();
            assertThat(detail.visibleOptionLabelExamples()).contains("New Look");
            assertThat(failure.getDiagnostics().withFailureMetadata(
                    "SELECT_CLOTHING_FIELDS", "failure", "cause",
                    "CLOTHING_SELECTION_FAILED"
            ).clothingSelection()).isEqualTo(detail);
            assertThat(page.locator("[data-field=brand]").innerText())
                    .isEqualTo("Vali bränd");
        });
    }

    @Test
    void recordsMissingControlAndRequestedValues() {
        withPage("<main><p>Raamatud</p></main>", page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft("S/M", null, null, null))
            );
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("size");
            assertThat(detail.requestedValues()).containsExactly("S/M");
            assertThat(detail.controlFound()).isFalse();
            assertThat(detail.controlVisible()).isFalse();
            assertThat(detail.controlEnabled()).isFalse();
            assertThat(detail.exactOptionMatchCount()).isNull();
            assertThat(detail.selectedValuesObserved()).isEmpty();
        });
    }

    @Test
    void recordsPreviouslySelectedValueWhenLaterExactOptionIsMissing() {
        withPage(clothingForm(), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft(null, null, "Punane, Tumesinine", null))
            );
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("color");
            assertThat(detail.requestedValues())
                    .containsExactly("Punane", "Tumesinine");
            assertThat(detail.exactOptionMatchCount()).isZero();
            assertThat(detail.selectedValuesObserved()).containsExactly("Punane");
            assertThat(detail.visibleOptionLabelExamples())
                    .contains("Punane", "Valge", "Punane ja valge");
        });
    }

    @Test
    void recordsDisabledControlWithoutOpeningOptions() {
        withPage(clothingForm().replace(
                "data-field=\"size\"", "data-field=\"size\" disabled"), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft("S/M", null, null, null))
            );
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("size");
            assertThat(detail.controlFound()).isTrue();
            assertThat(detail.controlVisible()).isTrue();
            assertThat(detail.controlEnabled()).isFalse();
            assertThat(detail.exactOptionMatchCount()).isNull();
        });
    }

    @Test
    void includesVisibleDisabledOptionAsExampleButNotAsExactMatch() {
        withPage(clothingForm().replace(
                "role=\"option\" onclick=\"choose('brand','New Look')\"",
                "role=\"option\" aria-disabled=\"true\" onclick=\"choose('brand','New Look')\""
        ), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft(null, "New Look", null, null))
            );
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.exactOptionMatchCount()).isZero();
            assertThat(detail.visibleOptionLabelExamples()).contains("New Look");
        });
    }

    @Test
    void rejectsUnconfirmedSelection() {
        withPage(clothingForm().replace(
                "choose('size','S/M')", "void(0)"), page -> {
            YagaPublishingFormException failure = catchThrowableOfType(
                    YagaPublishingFormException.class,
                    () -> automation.selectClothingFields(
                            page, draft("S/M", null, null, null))
            );
            assertThat(failure).hasMessageContaining(
                    "Suurus selection was not confirmed");
            var detail = failure.getDiagnostics().clothingSelection();
            assertThat(detail.field()).isEqualTo("size");
            assertThat(detail.exactOptionMatchCount()).isEqualTo(1);
            assertThat(detail.selectedValuesObserved()).isEmpty();
            assertThat(detail.visibleOptionLabelExamples()).isEmpty();
        });
    }

    @Test
    void rejectsValuesBeyondVisibleColorAndMaterialLimits() {
        withPage(clothingForm(), page -> {
            assertThatThrownBy(() -> automation.selectClothingFields(
                    page, draft(null, null, "Punane, Valge, Must", null)))
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("Värv accepts at most 2 values");
            assertThatThrownBy(() -> automation.selectClothingFields(
                    page, draft(null, null, null, "Vill, Teksa, Lina, Nahk, Siid, Puuvill")))
                    .isInstanceOf(YagaPublishingFormException.class)
                    .hasMessageContaining("Materjal accepts at most 5 values");
        });
    }

    private YagaListingDraftData draft(
            String size, String brand, String color, String material
    ) {
        return new YagaListingDraftData(
                1L, 1L, 1L, "shop", "description", new BigDecimal("15"),
                "EUR", ProductCondition.GOOD, List.of("Naistele"), List.of(),
                size, brand, color, material
        );
    }

    private void withPage(String html, java.util.function.Consumer<Page> assertion) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             Page page = browser.newPage()) {
            page.setContent(html);
            assertion.accept(page);
        }
    }

    private String clothingForm() {
        return """
                <div class="field"><label>Suurus (valikuline)</label>
                  <button role="combobox" data-field="size" onclick="openList('size')">Vali suurus</button></div>
                <div class="field"><label>Bränd (valikuline)</label>
                  <button role="combobox" data-field="brand" onclick="openList('brand')">Vali bränd</button></div>
                <div class="field"><label>Värv (valikuline)</label>
                  <button role="combobox" data-field="color" onclick="openList('color')">Vali kuni 2</button></div>
                <div class="field"><label>Materjal (valikuline)</label>
                  <button role="combobox" data-field="material" onclick="openList('material')">Vali kuni 5</button></div>
                <div><span>Hind</span><input type="text" placeholder="0"></div>
                <div role="listbox" id="size-list" style="display:none">
                  <div role="option" onclick="choose('size','S/M')"><span>S/M</span></div></div>
                <div role="listbox" id="brand-list" style="display:none">
                  <div role="option" onclick="choose('brand','New Look')"><span>New Look</span></div></div>
                <div role="listbox" id="color-list" style="display:none">
                  <div role="option" onclick="choose('color','Punane')"><span>Punane</span></div>
                  <div role="option" onclick="choose('color','Valge')"><span>Valge</span></div>
                  <div role="option" id="unrelated-option"><span>Punane ja valge</span></div></div>
                <div role="listbox" id="material-list" style="display:none">
                  <div role="option" onclick="choose('material','Vill')"><span>Vill</span></div>
                  <div role="option" onclick="choose('material','Teksa')"><span>Teksa</span></div></div>
                <script>
                  function openList(field) {
                    document.querySelectorAll('[role=listbox]').forEach(item => item.style.display='none');
                    document.getElementById(field+'-list').style.display='block';
                  }
                  function choose(field, value) {
                    const control = document.querySelector('[data-field='+field+']');
                    const multiple = field === 'color' || field === 'material';
                    const previous = control.innerText.startsWith('Vali') ? '' : control.innerText;
                    control.innerText = multiple && previous ? previous + ', ' + value : value;
                    if (!multiple) document.getElementById(field+'-list').style.display='none';
                  }
                  document.addEventListener('keydown', event => {
                    if (event.key !== 'Escape') return;
                    document.querySelectorAll('[role=listbox]').forEach(listbox => {
                      if (listbox.style.display === 'block') {
                        listbox.style.display = 'none';
                        listbox.setAttribute('data-closed', 'escape');
                      }
                    });
                  });
                </script>
                """;
    }

    private String liveBrandForm() {
        return """
                <button id="select-category" onclick="document.getElementById('brand-field').style.display='block'">Naistele</button>
                <div id="brand-field" style="display:none">
                  <h6>Bränd (valikuline)</h6>
                  <div><div><div>
                    <input id="brand-input" role="combobox" type="text" placeholder="Vali bränd"
                           aria-autocomplete="list" aria-expanded="false" autocomplete="off"
                           oninput="searchBrand()" onclick="searchBrand()">
                    <div><button type="button" aria-label="Clear">Clear</button>
                         <button type="button" aria-label="Open" onclick="searchBrand()">Open</button></div>
                  </div></div></div>
                </div>
                <ul id="brand-list" role="listbox" style="display:none">
                  <li role="option" onclick="selectBrand()"><span>Muu</span>
                    <small style="display:block">Vali see kui otsitavat brändi ei ole nimekirjas</small>
                  </li>
                </ul>
                <script>
                  function searchBrand() {
                    const input = document.getElementById('brand-input');
                    const option = document.querySelector('#brand-list [role=option]');
                    option.style.display = option.innerText.toLowerCase().includes(input.value.toLowerCase())
                      ? 'list-item' : 'none';
                    document.getElementById('brand-list').style.display = 'block';
                    input.setAttribute('aria-expanded', 'true');
                  }
                  function selectBrand() {
                    const input = document.getElementById('brand-input');
                    input.value = document.querySelector('#brand-list [role=option] span').innerText;
                    input.setAttribute('aria-expanded', 'false');
                    document.getElementById('brand-list').style.display = 'none';
                  }
                </script>
                """;
    }
}
