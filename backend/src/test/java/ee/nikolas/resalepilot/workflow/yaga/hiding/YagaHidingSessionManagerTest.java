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
import org.mockito.InOrder;
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

    private YagaHidingSessionManager.AutoOwner autoOwner() {
        return new YagaHidingSessionManager.AutoOwner(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1L);
    }

    private YagaHidePreparationResponse prepareAuto(YagaHidingSessionManager.AutoOwner owner) {
        var account = new ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount("Test", "nik-ar", null, 1);
        account.setId(1L);
        return manager.prepareForAuto(1L, account, owner);
    }

    @Test
    void ownedPrecheckFailureCanBeReleasedWithoutActionsAndNextJobCanPrepare() {
        properties.setConfirmEnabled(true);
        var owner = autoOwner();
        var prepared = prepareAuto(owner);
        when(preparationService.loadAndVerifyDraft(1L)).thenThrow(new IllegalStateException("precheck"));
        assertThatThrownBy(() -> manager.confirmForRefresh(prepared.preparationId(),
                new YagaHideConfirmRequest(prepared.confirmationToken(), "HIDE"))).hasMessage("precheck");
        assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner)).isTrue();
        assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner)).isFalse();
        assertThat(manager.status(prepared.preparationId()).status()).isEqualTo(YagaHidingStatus.CANCELLED);
        doReturn(draft()).when(preparationService).loadAndVerifyDraft(1L);
        assertThat(prepareAuto(autoOwner()).status()).isEqualTo("AWAITING_CONFIRMATION");
        verify(browserAutomation, never()).hidePreparedSession(any());
        verify(browserAutomation, times(1)).closeSession(browserSession);
        verifyNoInteractions(pageDataClient);
    }

    @Test
    void manualAndMismatchedOwnersAreUntouched() {
        var manual = manager.prepare(1L);
        assertThat(manager.releaseUnconfirmedAutoSession(manual.preparationId(), autoOwner())).isFalse();
        assertThat(manager.status(manual.preparationId()).status()).isEqualTo(YagaHidingStatus.AWAITING_CONFIRMATION);
        manager.cancel(manual.preparationId());
        var owner = autoOwner();
        var prepared = prepareAuto(owner);
        var mismatches = java.util.List.of(autoOwner(),
                new YagaHidingSessionManager.AutoOwner(owner.runId(), java.util.UUID.randomUUID(), owner.accountId()),
                new YagaHidingSessionManager.AutoOwner(owner.runId(), owner.jobId(), 2L));
        for (var mismatch : mismatches) {
            assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), mismatch)).isFalse();
        }
        assertThat(manager.releaseUnconfirmedAutoSession(java.util.UUID.randomUUID(), owner)).isFalse();
        assertThat(manager.status(prepared.preparationId()).status()).isEqualTo(YagaHidingStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void cleanupBeforeConfirmationInvalidatesTokenWithoutHideClick() {
        properties.setConfirmEnabled(true);
        var owner = autoOwner();
        var prepared = prepareAuto(owner);
        assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner)).isTrue();
        assertThatThrownBy(() -> manager.confirmForRefresh(prepared.preparationId(),
                new YagaHideConfirmRequest(prepared.confirmationToken(), "HIDE")))
                .isInstanceOf(YagaPublicationInvalidStateException.class);
        verify(browserAutomation, never()).hidePreparedSession(any());
    }

    @Test
    void expiredCleanupCannotReleaseNewOwner() throws Exception {
        properties.setConfirmationTtl(Duration.ofMillis(30));
        var owner = autoOwner();
        var prepared = prepareAuto(owner);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (manager.status(prepared.preparationId()).status() != YagaHidingStatus.EXPIRED &&
                System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(manager.status(prepared.preparationId()).status()).isEqualTo(YagaHidingStatus.EXPIRED);
        properties.setConfirmationTtl(Duration.ofMinutes(10));
        var next = prepareAuto(autoOwner());
        assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner)).isFalse();
        assertThat(manager.status(next.preparationId()).status()).isEqualTo(YagaHidingStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void cleanupQueuedBehindConfirmationCannotReleaseReplacementOwner() throws Exception {
        properties.setConfirmEnabled(true);
        var owner = autoOwner();
        var prepared = prepareAuto(owner);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        when(browserAutomation.hidePreparedSession(browserSession)).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("confirmation blocked");
            return new YagaHideResult(true, draft().oldExternalUrl(), Instant.now());
        });
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var confirmation = workers.submit(() -> manager.confirmForRefresh(prepared.preparationId(),
                    new YagaHideConfirmRequest(prepared.confirmationToken(), "HIDE")));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cleanup = workers.submit(() -> manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner));
            finish.countDown();
            assertThat(confirmation.get(3, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(YagaHidingStatus.HIDDEN);
            assertThat(cleanup.get(3, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            var next = prepareAuto(autoOwner());
            assertThat(manager.releaseUnconfirmedAutoSession(prepared.preparationId(), owner)).isFalse();
            assertThat(manager.status(next.preparationId()).status()).isEqualTo(YagaHidingStatus.AWAITING_CONFIRMATION);
        } finally {
            finish.countDown();
        }
        verify(browserAutomation, times(1)).hidePreparedSession(browserSession);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "delayed", "timeout", "error", "identity", "action"})
    void diagnosticSurvivesClosureWithoutChangingConfirmation(String scenario) {
        properties.setConfirmEnabled(true);
        properties.setHideDataPollTimeout(Duration.ofMillis(150));
        var target = hiddenData();
        if (scenario.equals("delayed")) {
            when(pageDataClient.getProduct(any())).thenReturn(publishedData(), publishedData(), data("not-visible", null));
        } else if (scenario.equals("timeout")) {
            when(pageDataClient.getProduct(any())).thenReturn(publishedData());
        } else if (scenario.equals("error")) {
            when(pageDataClient.getProduct(any())).thenThrow(new IllegalStateException("SECRET private@example.com"))
                    .thenReturn(target);
        } else if (scenario.equals("identity")) {
            var wrong = mock(YagaImportedProductData.class);
            when(wrong.shopSlug()).thenReturn("other-shop");
            when(wrong.status()).thenReturn("not-visible");
            when(pageDataClient.getProduct(any())).thenReturn(wrong);
        } else if (scenario.equals("action")) {
            when(browserAutomation.hidePreparedSession(browserSession)).thenThrow(new com.microsoft.playwright.TimeoutError("SECRET"));
        }
        var preparation = manager.prepare(1L);
        var result = manager.confirmForRefresh(preparation.preparationId(),
                new YagaHideConfirmRequest(preparation.confirmationToken(), "HIDE"));
        String diagnostic = manager.hideDiagnostic(preparation.preparationId());
        assertThat(diagnostic).contains("ACTION_INITIATED", "eventsDropped=").doesNotContain("SECRET", "private@example.com");
        boolean uncertain = List.of("timeout", "identity", "action").contains(scenario);
        assertThat(result.status()).isEqualTo(uncertain ? YagaHidingStatus.HIDE_RESULT_UNKNOWN : YagaHidingStatus.HIDDEN);
        if (scenario.equals("timeout")) assertThat(diagnostic).contains("verificationOutcome=TIMEOUT", "status=published");
        if (scenario.equals("identity")) assertThat(diagnostic).contains("identity=false", "accepted=false");
        if (scenario.equals("delayed")) assertThat(diagnostic).contains("attempts=3", "status=not-visible", "verificationOutcome=CONFIRMED");
        if (scenario.equals("error")) assertThat(diagnostic).contains("attempts=2", "READ_OR_RUNTIME_ERROR");
        if (scenario.equals("action")) assertThat(diagnostic).contains("BROWSER_ACTION_EXCEPTION", "TIMEOUT");
        verify(browserAutomation).closeSession(browserSession);
        verify(browserAutomation, times(1)).hidePreparedSession(browserSession);
        assertThatThrownBy(() -> manager.confirmForRefresh(preparation.preparationId(),
                new YagaHideConfirmRequest(preparation.confirmationToken(), "HIDE")))
                .isInstanceOf(YagaPublicationInvalidStateException.class);
        assertThat(manager.hideDiagnostic(preparation.preparationId())).isEqualTo(diagnostic);
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
    void uncertainClickIsNeverRetried() {
        properties.setConfirmEnabled(true);
        when(browserAutomation.hidePreparedSession(browserSession))
                .thenThrow(new com.microsoft.playwright.TimeoutError("Click outcome unknown"));
        var preparation = manager.prepare(1L);
        var request = new YagaHideConfirmRequest(preparation.confirmationToken(), "HIDE");

        var result = manager.confirmForRefresh(preparation.preparationId(), request);

        assertThat(result.status()).isEqualTo(YagaHidingStatus.HIDE_RESULT_UNKNOWN);
        assertThatThrownBy(() -> manager.confirmForRefresh(preparation.preparationId(), request))
                .isInstanceOf(YagaPublicationInvalidStateException.class);
        verify(browserAutomation, times(1)).hidePreparedSession(browserSession);
        verify(browserAutomation).closeSession(browserSession);
        verify(preparationService, never()).markOldHiddenAndNewCurrent(any(), any());
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
    void readinessReturnsToExpectedTargetBeforeInspectingReusedSession() {
        YagaHidePreparationResponse response = manager.prepare(1L);
        clearInvocations(browserAutomation);

        var readiness = manager.readiness(response.preparationId());

        InOrder order = inOrder(browserAutomation);
        order.verify(browserAutomation).ensureOnHideTarget(browserSession);
        order.verify(browserAutomation).inspectHideControl(browserSession);
        assertThat(readiness.candidateCount()).isEqualTo(1);
        assertThat(readiness.currentUrlHost()).isEqualTo("www.yaga.ee");
        assertThat(readiness.currentUrlPath())
                .isEqualTo("/nik-ar/toode/ip7p454fe6o");
        assertThat(readiness.expectedShopSlug()).isEqualTo("nik-ar");
        assertThat(readiness.expectedProductSlug())
                .isEqualTo("ip7p454fe6o");
        assertThat(readiness.targetUrlMatchesExpected()).isTrue();
        verify(browserAutomation, never()).hidePreparedSession(any());
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
