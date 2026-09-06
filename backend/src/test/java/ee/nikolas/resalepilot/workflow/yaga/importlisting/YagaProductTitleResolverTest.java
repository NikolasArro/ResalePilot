package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YagaProductTitleResolverTest {

    private final YagaProductTitleResolver resolver =
            new YagaProductTitleResolver();

    @Test
    void usesFirstNonBlankDescriptionLineWhenStructuredTitleIsMissing() {
        YagaImportedProductData data = data(
                null,
                "\n\n  First real line  \nSecond line"
        );

        assertThat(resolver.resolve(data))
                .contains("First real line");
    }

    @Test
    void normalizesRepeatedWhitespace() {
        YagaImportedProductData data = data(
                null,
                "  First\t\tline   with   spacing  "
        );

        assertThat(resolver.resolve(data))
                .contains("First line with spacing");
    }

    @Test
    void truncatesLongDescriptionTitleAtWordBoundary() {
        String description =
                "word ".repeat(29) + "lastworddoesnotfit";

        String resolved = resolver.resolve(data(null, description))
                .orElseThrow();

        assertThat(resolved).hasSizeLessThanOrEqualTo(150);
        assertThat(resolved).doesNotEndWith(" ");
        assertThat(resolved).doesNotContain("lastworddoesnotfit");
    }

    @Test
    void structuredTitleHasPriority() {
        YagaImportedProductData data = data(
                "  Structured   title  ",
                "Description title"
        );

        assertThat(resolver.resolve(data))
                .contains("Structured title");
    }

    @Test
    void emptyStructuredTitleAndDescriptionCannotResolve() {
        YagaImportedProductData data = data(
                " ",
                "\n  \t "
        );

        assertThat(resolver.resolve(data)).isEmpty();
    }

    private YagaImportedProductData data(
            String title,
            String description
    ) {
        return new YagaImportedProductData(
                1L,
                "nik-ar",
                "slug",
                title,
                description,
                BigDecimal.TEN,
                "EUR",
                "published",
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null
        );
    }
}
