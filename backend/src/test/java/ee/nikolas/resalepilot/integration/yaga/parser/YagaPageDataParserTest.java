package ee.nikolas.resalepilot.integration.yaga.parser;

import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;

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
                      "name": "Kalevipoeg",
                      "description": "Kalevipoeg",
                      "price": 17,
                      "currency": "EUR",
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

        assertThat(result.title())
                .isEqualTo("Kalevipoeg");

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
