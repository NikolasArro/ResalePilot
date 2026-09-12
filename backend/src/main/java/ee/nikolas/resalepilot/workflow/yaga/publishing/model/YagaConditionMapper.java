package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDataInvalidException;

public final class YagaConditionMapper {

    private YagaConditionMapper() {
    }

    public static YagaConditionSelection toYaga(
            ProductCondition condition
    ) {
        if (condition == null) {
            throw new YagaPublishingDataInvalidException(
                    "Product condition is required for Yaga publishing"
            );
        }

        return switch (condition) {
            case NEW_WITH_TAGS ->
                    new YagaConditionSelection("Uus");
            case NEW_WITHOUT_TAGS ->
                    new YagaConditionSelection("Uuev\u00e4\u00e4rne");
            case VERY_GOOD, GOOD ->
                    new YagaConditionSelection("Hea");
            case SATISFACTORY ->
                    new YagaConditionSelection("Keskmine");
        };
    }
}
