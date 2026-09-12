package ee.nikolas.resalepilot.workflow.yaga.refresh.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaRefreshPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        try {
                            var propertySources = new YamlPropertySourceLoader()
                                    .load(
                                            "application.yml",
                                            new ClassPathResource("application.yml")
                                    );
                            propertySources.forEach(propertySource ->
                                    context.getEnvironment()
                                            .getPropertySources()
                                            .addLast(propertySource));
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    })
                    .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultApplicationYamlBindsPositiveBatchSize() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(YagaRefreshProperties.class).batchSize())
                    .isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void batchSizeCannotExceedMaxBatchSize() {
        assertThatThrownBy(() ->
                properties(11, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void maxBatchSizeCannotExceedHardSafetyLimit() {
        assertThatThrownBy(() ->
                properties(10, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private YagaRefreshProperties properties(
            int batchSize,
            int maxBatchSize
    ) {
        return new YagaRefreshProperties(
                false,
                false,
                YagaRefreshMode.DRY_RUN,
                batchSize,
                YagaRefreshScheduleType.FIXED_DELAY,
                Duration.ofDays(1),
                "0 0 3 * * *",
                ZoneId.of("Europe/Tallinn"),
                maxBatchSize
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YagaRefreshProperties.class)
    static class PropertiesConfiguration {
    }
}
