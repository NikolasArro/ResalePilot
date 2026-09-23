package ee.nikolas.resalepilot.workflow.yaga.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class YagaAccountAuthStateResolverTest {

    private static final String LEGACY_AUTH_STATE_PATH =
            Path.of("playwright", ".auth", "yaga-state.json")
                    .toString();

    @Test
    void accountOneWithNullAuthStatePathResolvesLegacyState() {
        YagaAccount account = account(1L, null);

        Path resolved = YagaAccountAuthStateResolver.resolve(
                account,
                LEGACY_AUTH_STATE_PATH
        );

        assertThat(resolved)
                .isEqualTo(Path.of(LEGACY_AUTH_STATE_PATH)
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void nonDefaultAccountWithNullAuthStatePathResolvesAccountState() {
        YagaAccount account = account(2L, null);

        Path resolved = YagaAccountAuthStateResolver.resolve(
                account,
                LEGACY_AUTH_STATE_PATH
        );

        assertThat(resolved)
                .isEqualTo(Path.of(
                                "playwright",
                                ".auth",
                                "yaga-account-2-state.json"
                        )
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void nonDefaultAccountNeverResolvesLegacyStateWhenAuthPathIsNull() {
        YagaAccount account = account(2L, null);

        Path resolved = YagaAccountAuthStateResolver.resolve(
                account,
                LEGACY_AUTH_STATE_PATH
        );

        assertThat(resolved.getFileName().toString())
                .isEqualTo("yaga-account-2-state.json");
        assertThat(resolved)
                .isNotEqualTo(Path.of(LEGACY_AUTH_STATE_PATH)
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void explicitAuthStatePathOverridesGeneratedDefault() {
        Path explicit = Path.of(
                "playwright",
                ".auth",
                "custom-account-2.json"
        );
        YagaAccount account = account(2L, explicit.toString());

        Path resolved = YagaAccountAuthStateResolver.resolve(
                account,
                LEGACY_AUTH_STATE_PATH
        );

        assertThat(resolved)
                .isEqualTo(explicit.toAbsolutePath().normalize());
    }

    private YagaAccount account(Long id, String authStatePath) {
        YagaAccount account = new YagaAccount(
                "Account " + id,
                "shop-" + id,
                authStatePath,
                10
        );
        account.setId(id);
        return account;
    }
}
