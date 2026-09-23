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
    }

    @Test
    void wrongLoggedInShopIsRejected() {
        var result = YagaShopIdentityVerifier.verify(
                "second-shop",
                List.of("https://www.yaga.ee/pood/nik-ar")
        );

        assertThat(result.accepted()).isFalse();
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
        assertThat(result.safeMessage())
                .isEqualTo("Authenticated Yaga shop could not be verified");
    }
}
