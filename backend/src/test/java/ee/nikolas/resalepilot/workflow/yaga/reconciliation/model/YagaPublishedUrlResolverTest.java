package ee.nikolas.resalepilot.workflow.yaga.reconciliation.model;

import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaPublishedUrlResolverTest {

    private final YagaPublishedUrlResolver resolver =
            new YagaPublishedUrlResolver();

    @Test
    void publicProductUrlIsResolvedAsFinal() {
        YagaPublishedUrl resolved = resolver.resolve(
                "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q",
                "fallback"
        );

        assertThat(resolved.publicProductUrl()).isTrue();
        assertThat(resolved.publicUrl())
                .isEqualTo(
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
                );
        assertThat(resolved.shopSlug()).isEqualTo("nik-ar");
        assertThat(resolved.productSlug()).isEqualTo("5u7arpkm6q");
    }

    @Test
    void intermediatePublishUrlBuildsPublicCandidateWithShopSlug() {
        YagaPublishedUrl resolved = resolver.resolve(
                "https://www.yaga.ee/muuk/lisa-toode/5u7arpkm6q",
                "nik-ar"
        );

        assertThat(resolved.publicProductUrl()).isFalse();
        assertThat(resolved.publicUrl())
                .isEqualTo(
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
                );
        assertThat(resolved.shopSlug()).isEqualTo("nik-ar");
        assertThat(resolved.productSlug()).isEqualTo("5u7arpkm6q");
    }

    @Test
    void formLoginHomeAndUnexpectedUrlsAreRejected() {
        assertThatThrownBy(() -> resolver.resolve(
                "https://www.yaga.ee/muuk/lisa-toode",
                "nik-ar"
        ))
                .isInstanceOf(YagaPublishingFormException.class);

        assertThatThrownBy(() -> resolver.resolve(
                "https://www.yaga.ee/login",
                "nik-ar"
        ))
                .isInstanceOf(YagaPublishingFormException.class);

        assertThatThrownBy(() -> resolver.resolve(
                "http://www.yaga.ee/nik-ar/toode/5u7arpkm6q",
                "nik-ar"
        ))
                .isInstanceOf(YagaPublishingFormException.class);
    }
}
