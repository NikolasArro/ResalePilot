package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlaywrightYagaHidingBrowserAutomationTest {

    @Test
    void correctUrlDoesNotNavigateOrReloadAcrossReadinessChecks() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(draft().oldExternalUrl());
        var automation = new PlaywrightYagaHidingBrowserAutomation(new YagaHidingProperties());
        var session = session(page);
        for (int check = 0; check < 3; check++) {
            automation.ensureOnHideTarget(session);
        }
        verify(page, never()).navigate(anyString());
        verify(page, never()).reload();
    }

    @Test
    void differentUrlNavigatesOnce() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("about:blank");
        new PlaywrightYagaHidingBrowserAutomation(new YagaHidingProperties())
                .ensureOnHideTarget(session(page));
        verify(page).navigate(draft().oldExternalUrl());
        verify(page, never()).reload();
    }

    @Test
    void failedNavigationRetriesOnce() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("about:blank");
        when(page.navigate(draft().oldExternalUrl()))
                .thenThrow(new PlaywrightException("net::ERR_CONNECTION_RESET"))
                .thenReturn(null);
        new PlaywrightYagaHidingBrowserAutomation(new YagaHidingProperties())
                .ensureOnHideTarget(session(page));
        verify(page, times(2)).navigate(draft().oldExternalUrl());
        verify(page, never()).reload();
    }

    @Test
    void repeatedNavigationFailureStopsAfterTwoAttempts() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("about:blank");
        when(page.navigate(draft().oldExternalUrl()))
                .thenThrow(new PlaywrightException("net::ERR_CONNECTION_RESET"));
        assertThatThrownBy(() -> new PlaywrightYagaHidingBrowserAutomation(new YagaHidingProperties())
                .ensureOnHideTarget(session(page)))
                .isInstanceOf(YagaPublishingFormException.class);
        verify(page, times(2)).navigate(draft().oldExternalUrl());
        verify(page, never()).reload();
    }

    private PlaywrightYagaHidingBrowserAutomation.PlaywrightHidingSession session(Page page) {
        return new PlaywrightYagaHidingBrowserAutomation.PlaywrightHidingSession(
                draft(), draft().yagaAccountId(), null, null, null, page,
                draft().oldExternalUrl(), Path.of("unused-auth.json"));
    }

    @Test
    void rejectsMismatchedAccountBeforeOpeningHideSession() {
        YagaAccount account = account(2L, "other-shop");

        assertThatThrownBy(() -> new PlaywrightYagaHidingBrowserAutomation(
                new YagaHidingProperties()
        ).prepareSession(draft(), account))
                .isInstanceOf(YagaPublishingFormException.class)
                .hasMessage("Yaga account does not match hide draft");
    }

    private YagaHidingDraftData draft() {
        return new YagaHidingDraftData(
                1L,
                2L,
                1L,
                10L,
                "nik-ar",
                "27988552",
                "old-slug",
                "https://www.yaga.ee/nik-ar/toode/old-slug",
                "30796018",
                "new-slug",
                "https://www.yaga.ee/nik-ar/toode/new-slug"
        );
    }

    private YagaAccount account(Long id, String shopSlug) {
        YagaAccount account = new YagaAccount(
                "Account " + shopSlug,
                shopSlug,
                "../playwright/.auth/" + shopSlug + ".json",
                10
        );
        account.setId(id);
        return account;
    }
}
