package ee.nikolas.resalepilot.integration.yaga.diagnostic;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YagaAuthDiagnosticPathTest {

    @Test
    void setupAndCheckUseIdenticalGeneratedAccountPath() {
        Path setupPath = YagaAuthSetup.resolveAuthStatePath(
                new String[]{"--account-id", "2"}
        );
        Path checkPath = YagaAuthCheck.resolveAuthStatePath(
                new String[]{"--account-id", "2"}
        );

        assertThat(setupPath).isEqualTo(checkPath);
        assertThat(setupPath)
                .isEqualTo(Path.of(
                                "playwright",
                                ".auth",
                                "yaga-account-2-state.json"
                        )
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void setupAndCheckUseIdenticalExplicitPath() {
        String explicit = Path.of(
                "playwright",
                ".auth",
                "custom-account-state.json"
        ).toString();
        Path setupPath = YagaAuthSetup.resolveAuthStatePath(
                new String[]{
                        "--account-id", "2",
                        "--auth-state-path", explicit
                }
        );
        Path checkPath = YagaAuthCheck.resolveAuthStatePath(
                new String[]{
                        "--account-id", "2",
                        "--auth-state-path", explicit
                }
        );

        assertThat(setupPath).isEqualTo(checkPath);
        assertThat(setupPath)
                .isEqualTo(Path.of(explicit)
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void diagnosticsFailureDoesNotFailAuthCheck() {
        Page page = mock(Page.class);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        when(page.evaluate(anyString()))
                .thenThrow(new PlaywrightException(
                        "SecurityError: Failed to read the 'localStorage' property from 'Window': Access is denied for this document."
                ));

        assertThatCode(() -> YagaAuthCheck.printLiveStorageSummary(page))
                .doesNotThrowAnyException();
    }
}
