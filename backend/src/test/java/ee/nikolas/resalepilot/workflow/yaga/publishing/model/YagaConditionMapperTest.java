package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDataInvalidException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaConditionMapperTest {

    @Test
    void mapsProductConditionsToYagaKeyboardOffsets() {
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.NEW_WITH_TAGS
        ))
                .isEqualTo(new YagaConditionSelection("Uus", 0));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.NEW_WITHOUT_TAGS
        ))
                .isEqualTo(new YagaConditionSelection(
                        "Uuev\u00e4\u00e4rne",
                        1
                ));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.VERY_GOOD
        ))
                .isEqualTo(new YagaConditionSelection("Hea", 2));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.GOOD
        ))
                .isEqualTo(new YagaConditionSelection("Hea", 2));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.SATISFACTORY
        ))
                .isEqualTo(new YagaConditionSelection(
                        "Keskmine",
                        3
                ));
    }

    @Test
    void rejectsMissingCondition() {
        assertThatThrownBy(() -> YagaConditionMapper.toYaga(null))
                .isInstanceOf(YagaPublishingDataInvalidException.class)
                .hasMessageContaining("condition is required");
    }
}
