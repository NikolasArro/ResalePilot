package ee.nikolas.resalepilot.integration.yaga.parser;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.marketplace.entity.YagaDeliverySettings;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YagaPageDataParserTest {

    private final YagaPageDataParser parser =
            new YagaPageDataParser(new ObjectMapper());

    @Test
    void parsesKnownDeliveryIncludingDisabledOptionsAndExactCarrierCodes() {
        String json = """
                {"props":{"pageProps":{"initialProduct":{
                  "id":1,"slug":"delivery","price":15,
                  "shipping":{
                    "omniva":{"enabled":true,"selected_price":"small"},
                    "dpd":{"enabled":true,"selected_price":"xsmall"},
                    "smartpost":{"enabled":false,"selected_price":"small"},
                    "from_hand_to_hand":{"enabled":false,"selected_price":"zero"},
                    "upon_agreement":{"enabled":true,"selected_price":"zero"},
                    "bundling":{"enabled":true,"selected_price":"zero"}
                  }
                }}}}
                """;
        YagaDeliverySettings delivery = parser.parse(json).deliverySettings();
        assertThat(delivery).isNotNull();
        assertThat(delivery.getOmnivaEnabled()).isTrue();
        assertThat(delivery.getOmnivaSize().code()).isEqualTo("small");
        assertThat(delivery.getDpdSize().code()).isEqualTo("xsmall");
        assertThat(delivery.getSmartpostEnabled()).isFalse();
        assertThat(delivery.getSmartpostSize()).isNull();
        assertThat(delivery.getPickupEnabled()).isFalse();
        assertThat(delivery.getAgreementEnabled()).isTrue();
        assertThat(delivery.getBundlingEnabled()).isTrue();
    }

    @Test
    void absentShippingIsUnknownAndIncompleteShippingIsRejected() {
        assertThat(parser.parse("{\"id\":1,\"slug\":\"missing\",\"price\":1}")
                .deliverySettings()).isNull();
        assertThatThrownBy(() -> parser.parse("""
                {"id":1,"slug":"incomplete","price":1,
                 "shipping":{"omniva":{"enabled":false}}}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shipping option");
    }

    @Test
    void parsesClothingFromBothRealNextDataEnvelopes() {
        String pageProps = """
                {"pageProps":{"initialProduct":{
                  "id":1,"slug":"clothing","price":15,
                  "size":" XS ","brand":"Reserved",
                  "colors":[{"id":1,"name":"Must"},{"id":2,"name":"Valge"}],
                  "materials":[{"id":16,"name":"Vill"},{"id":49,"name":"Teksa"}]
                }}}
                """;
        for (String json : new String[]{pageProps, "{\"props\":" + pageProps + "}"}) {
            YagaImportedProductData data = parser.parse(json);
            assertThat(data.size()).isEqualTo("XS");
            assertThat(data.brand()).isEqualTo("Reserved");
            assertThat(data.colors()).containsExactly("Must", "Valge");
            assertThat(data.materials()).containsExactly("Vill", "Teksa");
        }
    }

    @Test
    void toleratesAbsentNullEmptyAndMalformedOptionalClothing() {
        for (String fields : new String[]{
                "",
                ",\"size\":null,\"brand\":null,\"colors\":null,\"materials\":null",
                ",\"size\":\" \",\"brand\":\"\",\"colors\":[],\"materials\":[]",
                ",\"size\":{},\"brand\":42,\"colors\":{},\"materials\":\"Vill\"",
                ",\"colors\":[null,42,{}, {\"name\":\" \"}],\"materials\":[{\"name\":false}]"
        }) {
            YagaImportedProductData data = parser.parse(
                    "{\"id\":1,\"slug\":\"empty\",\"price\":1" + fields + "}");
            assertThat(data.size()).isNull();
            assertThat(data.brand()).isNull();
            assertThat(data.colors()).isEmpty();
            assertThat(data.materials()).isEmpty();
        }
    }

    @Test
    void keepsValidNamesInSourceOrderAndRemovesDuplicates() {
        YagaImportedProductData data = parser.parse("""
                {"id":1,"slug":"mixed","price":1,
                 "colors":[{"name":" Must "},null,{}, {"name":"Must"},{"name":"Valge"}],
                 "materials":[{"name":"Vill"},{"name":123},{"name":" Teksa "}]}
                """);
        assertThat(data.colors()).containsExactly("Must", "Valge");
        assertThat(data.materials()).containsExactly("Vill", "Teksa");
    }

    @Test
    void parsesYagaNextPageData() {
        String json = """
                {
                  "pageProps": {
                    "availableConditions": [
                      {"id": 1, "name": "Uus"},
                      {"id": 3, "name": "Hea"}
                    ],
                    "initialProduct": {
                      "id": 27988552,
                      "slug": "ip7p454fe6o",
                      "name": "Kalevipoeg",
                      "description": "Kalevipoeg",
                      "price": 17,
                      "currency": "EUR",
                      "status": "published",
                      "likeCount": 3,
                      "createdAt": "2026-04-14T05:53:26.077Z",
                      "updatedAt": "2026-08-13T19:31:13.633Z",
                      "hiddenAt": null,
                      "deletedAt": null,
                      "shop": {
                        "activeSlug": "nik-ar"
                      },
                      "condition": {
                        "id": 3,
                        "name": "Hea"
                      },
                      "categories": [
                        {
                          "id": 8,
                          "parent": null,
                          "title": "Raamatud & ajakirjad",
                          "enabledFields": ["conditions"]
                        },
                        {
                          "id": 559,
                          "parent": 8,
                          "title": "Ajalugu",
                          "enabledFields": []
                        }
                      ],
                      "images": [
                        {
                          "id": "72baa6",
                          "original": "https://images.yaga.ee/image.jpeg",
                          "fileName": "72baa6.jpeg"
                        }
                      ]
                    }
                  }
                }
                """;

        YagaImportedProductData result =
                parser.parse(json);

        assertThat(result.externalId())
                .isEqualTo(27988552L);

        assertThat(result.shopSlug())
                .isEqualTo("nik-ar");

        assertThat(result.productSlug())
                .isEqualTo("ip7p454fe6o");

        assertThat(result.title())
                .isEqualTo("Kalevipoeg");

        assertThat(result.price())
                .isEqualByComparingTo(new BigDecimal("17"));

        assertThat(result.likeCount())
                .isEqualTo(3);

        assertThat(result.condition().name())
                .isEqualTo("Hea");

        assertThat(result.categoryPath())
                .extracting(
                        YagaImportedProductData.Category::title
                )
                .containsExactly(
                        "Raamatud & ajakirjad",
                        "Ajalugu"
                );

        assertThat(result.images())
                .hasSize(1);

        assertThat(result.hiddenAt())
                .isNull();
    }

    @Test
    void preservesYagaNotVisibleStatusForHiddenProducts() {
        String json = """
                {
                  "pageProps": {
                    "initialProduct": {
                      "id": 27988552,
                      "slug": "ip7p454fe6o",
                      "description": "Kalevipoeg",
                      "price": 17,
                      "currency": "EUR",
                      "status": "not-visible",
                      "hiddenAt": null,
                      "deletedAt": null,
                      "shop": {
                        "activeSlug": "nik-ar"
                      },
                      "condition": {
                        "id": 3,
                        "name": "Hea"
                      },
                      "categories": [],
                      "images": []
                    }
                  }
                }
                """;

        YagaImportedProductData result = parser.parse(json);

        assertThat(result.status()).isEqualTo("not-visible");
        assertThat(result.hiddenAt()).isNull();
    }

    @Test
    void parsesStructuredJsonLdProductNameWhenInitialProductNameIsBlank() {
        String json = """
                {
                  "pageProps": {
                    "initialProduct": {
                      "id": 30796018,
                      "slug": "5u7arpkm6q",
                      "name": null,
                      "description": "Description is not used as title",
                      "price": 17,
                      "currency": "EUR",
                      "status": "published",
                      "shop": {
                        "activeSlug": "nik-ar"
                      },
                      "categories": [],
                      "images": []
                    }
                  }
                }
                """;
        String html = """
                <html>
                  <head>
                    <title>Do not use this title</title>
                    <script type="application/ld+json">
                      {
                        "@context": "https://schema.org",
                        "@type": "Product",
                        "name": "Kalevipoeg"
                      }
                    </script>
                  </head>
                </html>
                """;

        YagaImportedProductData result =
                parser.parse(json, html);

        assertThat(result.title()).isEqualTo("Kalevipoeg");
    }

    @Test
    void keepsMissingTitleNullWhenNoStructuredProductNameExists() {
        String json = """
                {
                  "pageProps": {
                    "initialProduct": {
                      "id": 30796018,
                      "slug": "5u7arpkm6q",
                      "name": null,
                      "description": "Description is not used as title",
                      "price": 17,
                      "currency": "EUR",
                      "status": "published",
                      "shop": {
                        "activeSlug": "nik-ar"
                      },
                      "categories": [],
                      "images": []
                    }
                  }
                }
                """;
        String html = """
                <html>
                  <head>
                    <title>Do not use this title</title>
                  </head>
                </html>
                """;

        YagaImportedProductData result =
                parser.parse(json, html);

        assertThat(result.title()).isNull();
    }
}
