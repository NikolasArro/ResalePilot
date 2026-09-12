package ee.nikolas.resalepilot.workflow.yaga.hiding;

import ee.nikolas.resalepilot.workflow.yaga.common.YagaConfirmationTokenService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.automation.YagaHidingBrowserAutomation;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideResult;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;

import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationConfirmDisabledException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationForbiddenException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.nullable;

@ExtendWith(MockitoExtension.class)
class YagaHidingSessionManagerTest {

    @Mock
    private YagaHidingPreparationService preparationService;

    @Mock
    private YagaHidingBrowserAutomation browserAutomation;

    @Mock
    private YagaPageDataClient pageDataClient;

    private YagaHidingProperties properties;
    private YagaHidingSessionManager manager;
    private final YagaHidingPreparedBrowserSession browserSession =
            () -> draft();

    @BeforeEach
    void setUp() {
        properties = new YagaHidingProperties();
        properties.setConfirmationTtl(Duration.ofMinutes(10));
        properties.setHideDataPollTimeout(Duration.ofMillis(20));
        properties.setHideDataPollInterval(Duration.ofMillis(1));
        manager = new YagaHidingSessionManager(
                preparationService,
                browserAutomation,
                new YagaConfirmationTokenService(),
                properties,
                pageDataClient
        );

        lenient().when(preparationService.loadAndVerifyDraft(1L))
                .thenReturn(draft());
        lenient().doCallRealMethod()
                .when(preparationService)
                .isHiddenYagaStatus(any());
        lenient().when(browserAutomation.prepareSession(draft()))
                .thenReturn(browserSession);
        lenient().when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(readyInspection());
        lenient().when(browserAutomation.hidePreparedSession(browserSession))
                .thenReturn(new YagaHideResult(
                        true,
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        Instant.now()
                ));
        lenient().when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o"
        )).thenReturn(hiddenData());
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void confirmFlagFalseDoesNotClick() {
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        )).isInstanceOf(
                YagaPublicationConfirmDisabledException.class
        );

        verify(browserAutomation, never())
                .hidePreparedSession(any());
    }

    @Test
    void wrongTokenAndPhraseDoNotClick() {
        properties.setConfirmEnabled(true);
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest("wrong", "HIDE")
        )).isInstanceOf(YagaPublicationForbiddenException.class);
        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "PUBLISH"
                )
        )).isInstanceOf(YagaPublicationForbiddenException.class);

        verify(browserAutomation, never())
                .hidePreparedSession(any());
    }

    @Test
    void preClickValidationFailureDoesNotConsumeToken() {
        properties.setConfirmEnabled(true);
        YagaHidePreparationResponse response =
                manager.prepare(1L);
        doThrow(new YagaPublishingFormException("not ready"))
                .doNothing()
                .when(preparationService)
                .validateReadyInspection(eq(draft()), any());

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        )).isInstanceOf(YagaPublishingFormException.class);

        manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        verify(browserAutomation, times(1))
                .hidePreparedSession(browserSession);
    }

    @Test
    void exactPeidaConfirmationClicksOnceAndSwitchesDbCurrent() {
        properties.setConfirmEnabled(true);
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        var confirm = manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(confirm.status()).isEqualTo(YagaHidingStatus.HIDDEN);
        assertThat(confirm.hidden()).isTrue();
        verify(browserAutomation, times(1))
                .hidePreparedSession(browserSession);
        verify(preparationService, times(1))
                .markOldHiddenAndNewCurrent(
                        eq(draft()),
                        nullable(Instant.class)
                );
    }

    @Test
    void refreshConfirmationUsesSameClickPathWithoutIndependentDbSync() {
        properties.setConfirmEnabled(true);
        YagaHidePreparationResponse response = manager.prepare(1L);

        var confirm = manager.confirmForRefresh(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(confirm.status()).isEqualTo(YagaHidingStatus.HIDDEN);
        verify(browserAutomation, times(1))
                .hidePreparedSession(browserSession);
        verify(preparationService, never())
                .markOldHiddenAndNewCurrent(any(), any());
    }

    @Test
    void refreshConfirmationDoesNotTreatSoldWithHiddenTimestampAsHidden() {
        properties.setConfirmEnabled(true);
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o"
        )).thenReturn(data("sold", Instant.now()));
        YagaHidePreparationResponse response = manager.prepare(1L);

        var confirm = manager.confirmForRefresh(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(confirm.status())
                .isEqualTo(YagaHidingStatus.HIDE_RESULT_UNKNOWN);
        verify(preparationService, never())
                .markOldHiddenAndNewCurrent(any(), any());
    }

    @Test
    void repeatedConfirmDoesNotClickAgain() {
        properties.setConfirmEnabled(true);
        YagaHidePreparationResponse response =
                manager.prepare(1L);
        manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        )).isInstanceOf(YagaPublicationInvalidStateException.class);

        verify(browserAutomation, times(1))
                .hidePreparedSession(browserSession);
    }

    @Test
    void pollingTimeoutLeavesUnknownAndDoesNotUpdateDb() {
        properties.setConfirmEnabled(true);
        when(pageDataClient.getProduct(
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o"
        )).thenReturn(publishedData());
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        var confirm = manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(confirm.status())
                .isEqualTo(YagaHidingStatus.HIDE_RESULT_UNKNOWN);
        verify(preparationService, never())
                .markOldHiddenAndNewCurrent(any(), any());
    }

    @Test
    void dbFailureAfterConfirmedHideIsTerminalWithoutSecondClick() {
        properties.setConfirmEnabled(true);
        doThrow(new IllegalStateException("db down"))
                .when(preparationService)
                .markOldHiddenAndNewCurrent(any(), any());
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        var confirm = manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(confirm.status())
                .isEqualTo(YagaHidingStatus.HIDDEN_DB_SYNC_FAILED);
        assertThatThrownBy(() -> manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        )).isInstanceOf(YagaPublicationInvalidStateException.class);
        verify(browserAutomation, times(1))
                .hidePreparedSession(browserSession);
    }

    @Test
    void cancelClosesResourcesWithoutClick() {
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        var status = manager.cancel(response.preparationId());

        assertThat(status.status())
                .isEqualTo(YagaHidingStatus.CANCELLED);
        verify(browserAutomation).closeSession(browserSession);
        verify(browserAutomation, never())
                .hidePreparedSession(any());
    }

    @Test
    void expiryClosesResourcesWithoutClick() {
        properties.setConfirmationTtl(Duration.ofMillis(1));
        manager = new YagaHidingSessionManager(
                preparationService,
                browserAutomation,
                new YagaConfirmationTokenService(),
                properties,
                pageDataClient
        );
        YagaHidePreparationResponse response =
                manager.prepare(1L);

        awaitExpiry();
        var status = manager.status(response.preparationId());

        assertThat(status.status())
                .isEqualTo(YagaHidingStatus.EXPIRED);
        verify(browserAutomation, atLeastOnce())
                .closeSession(browserSession);
        verify(browserAutomation, never())
                .hidePreparedSession(any());
    }

    @Test
    void preparationAndConfirmationUseSameExecutorThread() {
        properties.setConfirmEnabled(true);
        List<String> threadNames = new ArrayList<>();
        when(browserAutomation.prepareSession(draft()))
                .thenAnswer(invocation -> {
                    threadNames.add(Thread.currentThread().getName());
                    return browserSession;
                });
        when(browserAutomation.inspectHideControl(browserSession))
                .thenAnswer(invocation -> {
                    threadNames.add(Thread.currentThread().getName());
                    return readyInspection();
                });
        when(browserAutomation.hidePreparedSession(browserSession))
                .thenAnswer(invocation -> {
                    threadNames.add(Thread.currentThread().getName());
                    return new YagaHideResult(
                            true,
                            "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                            Instant.now()
                    );
                });
        YagaHidePreparationResponse response =
                manager.prepare(1L);
        manager.readiness(response.preparationId());
        manager.confirm(
                response.preparationId(),
                new YagaHideConfirmRequest(
                        response.confirmationToken(),
                        "HIDE"
                )
        );

        assertThat(threadNames).isNotEmpty();
        assertThat(threadNames.stream().distinct().count())
                .isEqualTo(1);
    }

    private void awaitExpiry() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private YagaHideControlInspection readyInspection() {
        return new YagaHideControlInspection(
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                "27988552",
                "ip7p454fe6o",
                1,
                1,
                1,
                "Peida",
                "Peida",
                "button",
                "button",
                true,
                Instant.now(),
                null
        );
    }

    private YagaHidingDraftData draft() {
        return new YagaHidingDraftData(
                1L,
                2L,
                10L,
                "nik-ar",
                "27988552",
                "ip7p454fe6o",
                "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                "30796018",
                "5u7arpkm6q",
                "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q"
        );
    }

    private YagaImportedProductData hiddenData() {
        return data("not-visible", null);
    }

    private YagaImportedProductData publishedData() {
        return data("published", null);
    }

    private YagaImportedProductData data(
            String status,
            Instant hiddenAt
    ) {
        return new YagaImportedProductData(
                27988552L,
                "nik-ar",
                "ip7p454fe6o",
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                status,
                new YagaImportedProductData.Condition(3L, "Hea"),
                List.of(),
                List.of(),
                Instant.now(),
                Instant.now(),
                hiddenAt,
                null
        );
    }
}
