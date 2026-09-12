package ee.nikolas.resalepilot.workflow.yaga.publishing.model;

import ee.nikolas.resalepilot.product.entity.Product;

import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDataInvalidException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaConditionMapperTest {

    @Test
    void mapsProductConditionsToExactYagaLabels() {
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.NEW_WITH_TAGS
        ))
                .isEqualTo(new YagaConditionSelection("Uus"));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.NEW_WITHOUT_TAGS
        ))
                .isEqualTo(new YagaConditionSelection(
                        "Uuev\u00e4\u00e4rne"
                ));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.VERY_GOOD
        ))
                .isEqualTo(new YagaConditionSelection("Hea"));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.GOOD
        ))
                .isEqualTo(new YagaConditionSelection("Hea"));
        assertThat(YagaConditionMapper.toYaga(
                ProductCondition.SATISFACTORY
        ))
                .isEqualTo(new YagaConditionSelection(
                        "Keskmine"
                ));
    }

    @Test
    void rejectsMissingCondition() {
        assertThatThrownBy(() -> YagaConditionMapper.toYaga(null))
                .isInstanceOf(YagaPublishingDataInvalidException.class)
                .hasMessageContaining("condition is required");
    }
}
