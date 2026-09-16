package ee.nikolas.resalepilot.workflow.yaga.auth;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class YagaPlaywrightAuthStateTest {

    @TempDir
    Path tempDir;

    @Test
    void storageStateOptionsPersistIndexedDb() {
        Path authStatePath = tempDir.resolve("yaga-state.json");

        BrowserContext.StorageStateOptions options =
                YagaPlaywrightAuthState.storageStateOptions(authStatePath);

        assertThat(options.indexedDB).isTrue();
        assertThat(options.path).isEqualTo(authStatePath);
    }

    @Test
    void summarizeReportsSafeStorageMetadataWithoutValues()
            throws Exception {
        Path authStatePath = tempDir.resolve("yaga-state.json");
        Files.writeString(
                authStatePath,
                """
                        {
                          "cookies": [
                            {
                              "name": "token",
                              "value": "secret-cookie-value",
                              "domain": "www.yaga.ee",
                              "path": "/",
                              "expires": 1790164678,
                              "sameSite": "Lax"
                            }
                          ],
                          "origins": [
                            {
                              "origin": "https://www.yaga.ee",
                              "localStorage": [
                                {
                                  "name": "auth-key",
                                  "value": "secret-local-value"
                                }
                              ],
                              "indexedDB": [
                                {
                                  "name": "firebaseLocalStorageDb"
                                }
                              ]
                            }
                          ]
                        }
                        """
        );

        YagaPlaywrightAuthState.AuthStateSummary summary =
                YagaPlaywrightAuthState.summarize(authStatePath);

        assertThat(summary.resolvedAuthStatePath())
                .isEqualTo(authStatePath.toAbsolutePath().normalize());
        assertThat(summary.fileExists()).isTrue();
        assertThat(summary.savedCookieCount()).isEqualTo(1);
        assertThat(summary.indexedDbEntriesPresent()).isTrue();
        assertThat(summary.savedCookieMetadata())
                .containsExactly(
                        "www.yaga.ee / token Lax expires=1790164678"
                );
        assertThat(summary.savedOrigins())
                .containsExactly(
                        "https://www.yaga.ee localStorageKeys=[auth-key] " +
                                "indexedDbDatabases=[firebaseLocalStorageDb]"
                );
        assertThat(summary.toString())
                .doesNotContain("secret-cookie-value")
                .doesNotContain("secret-local-value");
    }

    @Test
    void restoreSessionStorageIsNoOpWhenSidecarIsMissing() {
        BrowserContext context = mock(BrowserContext.class);

        boolean restored = YagaPlaywrightAuthState.restoreSessionStorage(
                context,
                tempDir.resolve("missing-state.json")
        );

        assertThat(restored).isFalse();
        verify(context, never()).addInitScript(contains("sessionStorage"));
    }

    @Test
    void restoreSessionStorageInstallsInitScriptWhenSidecarExists()
            throws Exception {
        Path authStatePath = tempDir.resolve("yaga-state.json");
        Path sessionStoragePath =
                YagaPlaywrightAuthState.sessionStoragePath(authStatePath);
        Files.writeString(
                sessionStoragePath,
                """
                        {
                          "origins": [
                            {
                              "origin": "https://www.yaga.ee",
                              "sessionStorage": [
                                {
                                  "name": "session-auth-key",
                                  "value": "secret-session-value"
                                }
                              ]
                            }
                          ]
                        }
                        """
        );
        BrowserContext context = mock(BrowserContext.class);

        boolean restored = YagaPlaywrightAuthState.restoreSessionStorage(
                context,
                authStatePath
        );

        assertThat(restored).isTrue();
        verify(context).addInitScript(contains("window.sessionStorage"));
    }
}
