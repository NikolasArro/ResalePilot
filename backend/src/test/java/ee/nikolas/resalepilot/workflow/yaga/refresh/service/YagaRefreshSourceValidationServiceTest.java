package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class YagaRefreshSourceValidationServiceTest {
    private final YagaRefreshSourceValidationService.Source source =
            new YagaRefreshSourceValidationService.Source(
                    "29577482", "w-a-k-a", "rbpod2rds44",
                    "https://www.yaga.ee/w-a-k-a/toode/rbpod2rds44");
    private YagaPageDataClient client;
    private YagaImportedProductData data;
    private YagaRefreshSourceValidationService service;

    @BeforeEach
    void setUp() {
        client = mock(YagaPageDataClient.class);
        data = mock(YagaImportedProductData.class);
        service = new YagaRefreshSourceValidationService(client);
        when(client.getProduct(source.url())).thenReturn(data);
        when(data.externalId()).thenReturn(29577482L);
        when(data.shopSlug()).thenReturn("w-a-k-a");
        when(data.productSlug()).thenReturn("rbpod2rds44");
        when(data.status()).thenReturn("published");
    }

    @Test
    void exactPublishedSourcePassesUsingReadOnlyDetailClient() {
        service.requireActive(source);
        verify(client).getProduct(source.url());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"deleted", "sold", "hidden", "peidetud", "not-visible",
            "unavailable", "pending", "unknown", "PUBLISHED"})
    void anyUnconfirmedActiveStatusFailsClosed(String status) {
        when(data.status()).thenReturn(status);
        assertThatThrownBy(() -> service.requireActive(source))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .hasMessageContaining("SOURCE_NOT_ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "deleted"})
    void inactiveTimestampOverridesPublishedStatus(String timestamp) {
        if (timestamp.equals("hidden")) {
            when(data.hiddenAt()).thenReturn(Instant.now());
        } else {
            when(data.deletedAt()).thenReturn(Instant.now());
        }
        assertThatThrownBy(() -> service.requireActive(source))
                .hasMessageContaining("SOURCE_NOT_ACTIVE")
                .isInstanceOf(ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "shop", "slug", "missingId", "missingData"})
    void identityMustMatchEveryComponent(String mismatch) {
        switch (mismatch) {
            case "id" -> when(data.externalId()).thenReturn(31492831L);
            case "shop" -> when(data.shopSlug()).thenReturn("another-shop");
            case "slug" -> when(data.productSlug()).thenReturn("another-slug");
            case "missingId" -> when(data.externalId()).thenReturn(null);
            case "missingData" -> when(client.getProduct(source.url())).thenReturn(null);
        }
        assertThatThrownBy(() -> service.requireActive(source))
                .hasMessageContaining("SOURCE_IDENTITY_UNVERIFIED");
    }

    @Test
    void httpOrParseFailureStopsSafelyWithoutExposingRemoteMessage() {
        when(client.getProduct(source.url())).thenThrow(new IllegalStateException("sensitive response"));
        assertThatThrownBy(() -> service.requireActive(source))
                .hasMessageContaining("SOURCE_VALIDATION_UNAVAILABLE")
                .hasMessageNotContaining("sensitive response");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sold", "hidden", "deleted", "not-visible"})
    void onlyVerifiedInactiveStatesHaveTypedException(String status) {
        when(data.status()).thenReturn(status);
        assertThatThrownBy(() -> service.requireActive(source)).isInstanceOf(
                ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException.class);
        when(data.externalId()).thenReturn(123L);
        assertThatThrownBy(() -> service.requireActive(source))
                .hasMessageContaining("SOURCE_IDENTITY_UNVERIFIED")
                .isNotInstanceOf(ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "pending", "unavailable", "peidetud", "PUBLISHED"})
    void ambiguousStatesAreNeverTypedInactive(String status) {
        when(data.status()).thenReturn(status);
        assertThatThrownBy(() -> service.requireActive(source))
                .isInstanceOf(YagaRefreshInvalidStateException.class)
                .isNotInstanceOf(ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException.class);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @ValueSource(ints = {-1, 0, 1, 5})
    void onlyBatchPolicyRequiresExplicitZero(Integer count) {
        when(data.likeCount()).thenReturn(count);
        service.requireActive(source); // targeted/scheduled checks remain unrestricted
        if (Integer.valueOf(0).equals(count)) {
            service.requireActiveZeroLikes(source);
        } else {
            assertThatThrownBy(() -> service.requireActiveZeroLikes(source))
                    .hasMessageContaining(count == null || count < 0
                            ? "SOURCE_LIKE_COUNT_UNVERIFIED" : "SOURCE_HAS_LIKES");
        }
    }

    @Test
    void batchVerificationAlsoFailsClosedForIdentityAndNetworkErrors() {
        when(data.externalId()).thenReturn(123L);
        assertThatThrownBy(() -> service.requireActiveZeroLikes(source)).hasMessageContaining("SOURCE_IDENTITY_UNVERIFIED");
        when(client.getProduct(source.url())).thenThrow(new IllegalStateException("SECRET"));
        assertThatThrownBy(() -> service.requireActiveZeroLikes(source)).hasMessageContaining("SOURCE_VALIDATION_UNAVAILABLE")
                .hasMessageNotContaining("SECRET");
    }

}
