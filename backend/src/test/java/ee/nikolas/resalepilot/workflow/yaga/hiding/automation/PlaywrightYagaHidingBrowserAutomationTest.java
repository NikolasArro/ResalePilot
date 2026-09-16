package ee.nikolas.resalepilot.workflow.yaga.hiding.automation;

import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightYagaHidingBrowserAutomationTest {

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
