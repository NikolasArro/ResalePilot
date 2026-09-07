package ee.nikolas.resalepilot.workflow.yaga.batcharchive.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaBatchArchivePropertiesTest {

    @Test
    void acceptsValidLimits() {
        assertThatCode(() ->
                new YagaBatchArchiveProperties(false, 10, 100)
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidLimits() {
        assertThatThrownBy(() ->
                new YagaBatchArchiveProperties(false, 0, 100)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new YagaBatchArchiveProperties(false, 10, 0)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new YagaBatchArchiveProperties(false, 101, 100)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new YagaBatchArchiveProperties(false, 10, 101)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new YagaBatchArchiveProperties(false, 20, 10)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
