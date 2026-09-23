package ee.nikolas.resalepilot.workflow.yaga.auth;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void inspectLivePageStorageSkipsAboutBlank() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("about:blank");

        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);

        assertThat(summary.storageAvailable()).isFalse();
        assertThat(summary.pageUrl()).isEqualTo("about:blank");
        assertThat(summary.unavailableReason())
                .isEqualTo("non-yaga-http-document");
        verify(page, never()).evaluate(anyString());
    }

    @Test
    void inspectLivePageStorageReturnsUnavailableWhenStorageIsInaccessible() {
        Page page = mock(Page.class);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        when(page.evaluate(anyString()))
                .thenThrow(new PlaywrightException(
                        "SecurityError: Failed to read the 'localStorage' property from 'Window': Access is denied for this document."
                ));

        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);

        assertThat(summary.storageAvailable()).isFalse();
        assertThat(summary.pageUrl())
                .isEqualTo("https://www.yaga.ee/muuk/lisa-toode");
        assertThat(summary.unavailableReason())
                .isEqualTo("storage-access-denied");
    }

    @Test
    void inspectLivePageStorageReportsNormalYagaPageStorage() {
        Page page = mock(Page.class);
        when(page.url())
                .thenReturn("https://www.yaga.ee/muuk/lisa-toode");
        when(page.evaluate(anyString()))
                .thenReturn(
                        """
                                {
                                  "origin": "https://www.yaga.ee",
                                  "storageAvailable": true,
                                  "localStorageKeys": ["auth-key"],
                                  "sessionStorageKeys": ["session-key"],
                                  "indexedDbNames": ["firebaseLocalStorageDb"]
                                }
                                """
                );

        YagaPlaywrightAuthState.LiveStorageSummary summary =
                YagaPlaywrightAuthState.inspectLivePageStorage(page);

        assertThat(summary.storageAvailable()).isTrue();
        assertThat(summary.origin()).isEqualTo("https://www.yaga.ee");
        assertThat(summary.localStorageKeys()).containsExactly("auth-key");
        assertThat(summary.sessionStorageKeys()).containsExactly("session-key");
        assertThat(summary.indexedDbNames())
                .containsExactly("firebaseLocalStorageDb");
    }
}
