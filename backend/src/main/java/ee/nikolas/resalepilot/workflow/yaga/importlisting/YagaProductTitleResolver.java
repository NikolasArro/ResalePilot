package ee.nikolas.resalepilot.workflow.yaga.importlisting;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class YagaProductTitleResolver {

    public static final int PRODUCT_TITLE_MAX_LENGTH = 150;

    public Optional<String> resolve(YagaImportedProductData data) {
        if (data == null) {
            return Optional.empty();
        }

        Optional<String> structuredTitle = validate(data.title());
        if (structuredTitle.isPresent()) {
            return structuredTitle;
        }

        return firstDescriptionLine(data.description())
                .map(this::truncateToProductTitleLength)
                .flatMap(this::validate);
    }

    public Optional<String> validate(String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalizeWhitespace(title);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        return normalized.length() <= PRODUCT_TITLE_MAX_LENGTH
                ? Optional.of(normalized)
                : Optional.empty();
    }

    private Optional<String> firstDescriptionLine(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }

        for (String line : description.split("\\R")) {
            String normalized = normalizeWhitespace(line);
            if (!normalized.isBlank()) {
                return Optional.of(normalized);
            }
        }

        return Optional.empty();
    }

    private String normalizeWhitespace(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ");
    }

    private String truncateToProductTitleLength(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= PRODUCT_TITLE_MAX_LENGTH) {
            return normalized;
        }

        int wordBoundary = normalized.lastIndexOf(
                ' ',
                PRODUCT_TITLE_MAX_LENGTH
        );
        if (wordBoundary > 0) {
            return normalized.substring(0, wordBoundary).trim();
        }

        return normalized
                .substring(0, PRODUCT_TITLE_MAX_LENGTH)
                .trim();
    }
}
