package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YagaShopIdentityVerifierTest {

    @Test
    void correctLoggedInShopIsAccepted() {
        var result = YagaShopIdentityVerifier.verify(
                "second-shop",
                List.of(
                        "https://www.yaga.ee/",
                        "https://www.yaga.ee/pood/second-shop"
                )
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.verifiedShopSlug()).isEqualTo("second-shop");
        assertThat(result.verificationSource()).isEqualTo("SHOP_PROFILE_URL");
    }

    @Test
    void correctSecondShopRootUrlIsAccepted() {
        var result = YagaShopIdentityVerifier.verify(
                "w-a-k-a",
                List.of(
                        "https://www.yaga.ee/",
                        "https://www.yaga.ee/w-a-k-a"
                )
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.verifiedShopSlug()).isEqualTo("w-a-k-a");
        assertThat(result.detectedShopSlug()).isEqualTo("w-a-k-a");
        assertThat(result.verificationSource()).isEqualTo("SHOP_ROOT_URL");
    }

    @Test
    void wrongLoggedInShopIsRejected() {
        var result = YagaShopIdentityVerifier.verify(
                "w-a-k-a",
                List.of("https://www.yaga.ee/pood/nik-ar")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.detectedShopSlug()).isEqualTo("nik-ar");
        assertThat(result.verificationSource()).isEqualTo("SHOP_PROFILE_URL");
        assertThat(result.failureReason())
                .isEqualTo("DETECTED_SHOP_DOES_NOT_MATCH_EXPECTED");
        assertThat(result.safeMessage())
                .isEqualTo(
                        "Authenticated Yaga shop does not match selected account"
                );
    }

    @Test
    void missingShopEvidenceIsRejected() {
        var result = YagaShopIdentityVerifier.verify(
                "second-shop",
                List.of("https://www.yaga.ee/")
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.detectedShopSlug()).isNull();
        assertThat(result.verificationSource()).isNull();
        assertThat(result.failureReason())
                .isEqualTo("NO_SHOP_URL_SIGNAL_FOUND");
        assertThat(result.safeMessage())
                .isEqualTo("Authenticated Yaga shop could not be verified");
    }

    @Test
    void ambiguousRootShopEvidenceIsRejected() {
        var result = YagaShopIdentityVerifier.verify(
                "w-a-k-a",
                List.of(
                        "https://www.yaga.ee/w-a-k-a",
                        "https://www.yaga.ee/nik-ar"
                )
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.safeMessage())
                .isEqualTo("Authenticated Yaga shop could not be verified");
        assertThat(result.failureReason())
                .isEqualTo("MULTIPLE_SHOP_SLUGS_DETECTED");
    }

    @Test
    void authenticatedSecondShopIsAccepted() {
        var result = YagaShopIdentityVerifier.verifyAuthenticatedShop(
                "w-a-k-a",
                "w-a-k-a",
                "AUTHENTICATED_UI_SHOP_LINK",
                null
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.verifiedShopSlug()).isEqualTo("w-a-k-a");
        assertThat(result.detectedShopSlug()).isEqualTo("w-a-k-a");
        assertThat(result.verificationSource())
                .isEqualTo("AUTHENTICATED_UI_SHOP_LINK");
    }

    @Test
    void authenticatedFirstShopIsRejectedForSecondAccount() {
        var result = YagaShopIdentityVerifier.verifyAuthenticatedShop(
                "w-a-k-a",
                "nik-ar",
                "AUTHENTICATED_UI_SHOP_LINK",
                null
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.detectedShopSlug()).isEqualTo("nik-ar");
        assertThat(result.verificationSource())
                .isEqualTo("AUTHENTICATED_UI_SHOP_LINK");
        assertThat(result.failureReason())
                .isEqualTo(
                        "DETECTED_AUTHENTICATED_SHOP_DOES_NOT_MATCH_EXPECTED"
                );
    }

    @Test
    void publicSecondShopPageDoesNotOverrideAuthenticatedFirstShop() {
        var result = YagaShopIdentityVerifier.verifyAuthenticatedShop(
                "w-a-k-a",
                "nik-ar",
                "AUTHENTICATED_NEXT_DATA",
                null
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.detectedShopSlug()).isEqualTo("nik-ar");
        assertThat(result.safeMessage())
                .isEqualTo(
                        "Authenticated Yaga shop does not match selected account"
                );
    }

    @Test
    void missingAuthenticatedIdentityIsRejected() {
        var result = YagaShopIdentityVerifier.verifyAuthenticatedShop(
                "w-a-k-a",
                null,
                "AUTHENTICATED_IDENTITY",
                "NO_AUTHENTICATED_SHOP_SIGNAL_FOUND"
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.detectedShopSlug()).isNull();
        assertThat(result.verificationSource())
                .isEqualTo("AUTHENTICATED_IDENTITY");
        assertThat(result.failureReason())
                .isEqualTo("NO_AUTHENTICATED_SHOP_SIGNAL_FOUND");
    }

    @Test
    void authenticatedIdentityIgnoresBackgroundShopTabs() {
        var result = YagaShopIdentityVerifier.verifyAuthenticatedShop(
                "w-a-k-a",
                "w-a-k-a",
                "AUTHENTICATED_UI_SHOP_LINK",
                "BACKGROUND_TABS_CONTAIN_NIK_AR_AND_KKK"
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.detectedShopSlug()).isEqualTo("w-a-k-a");
    }
}
