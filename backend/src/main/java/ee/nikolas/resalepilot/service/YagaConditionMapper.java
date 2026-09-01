package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.entity.ProductCondition;
import ee.nikolas.resalepilot.exception.YagaPublishingDataInvalidException;

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
                    new YagaConditionSelection("Uus", 0);
            case NEW_WITHOUT_TAGS ->
                    new YagaConditionSelection("Uuev\u00e4\u00e4rne", 1);
            case VERY_GOOD, GOOD ->
                    new YagaConditionSelection("Hea", 2);
            case SATISFACTORY ->
                    new YagaConditionSelection("Keskmine", 3);
        };
    }
}
