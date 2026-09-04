package ee.nikolas.resalepilot.yaga;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class YagaPageDataParserTest {

    private final YagaPageDataParser parser =
            new YagaPageDataParser(new ObjectMapper());

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
                      "description": "Kalevipoeg",
                      "price": 17,
                      "currency": "€",
                      "status": "published",
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

        assertThat(result.price())
                .isEqualByComparingTo(new BigDecimal("17"));

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
}
