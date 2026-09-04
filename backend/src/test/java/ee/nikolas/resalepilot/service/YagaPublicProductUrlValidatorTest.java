package ee.nikolas.resalepilot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YagaPublicProductUrlValidatorTest {

    @Test
    void acceptsOldPublicProductUrl() {
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isTrue();
    }

    @Test
    void rejectsNewPublicProductUrl() {
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
    }

    @Test
    void rejectsEditUrl() {
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o/muuda",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
    }

    @Test
    void rejectsWrongShopHostSchemeQueryOrFragment() {
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/other/toode/ip7p454fe6o",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://evil.example/nik-ar/toode/ip7p454fe6o",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "http://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o?x=1",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
        assertThat(YagaPublicProductUrlValidator
                .isExpectedPublicProductUrl(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o#hide",
                        "nik-ar",
                        "ip7p454fe6o"
                )).isFalse();
    }
}
