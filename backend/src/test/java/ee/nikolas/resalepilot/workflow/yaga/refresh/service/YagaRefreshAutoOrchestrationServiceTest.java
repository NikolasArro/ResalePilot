package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingDriveDownloadException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingOperationStage;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshSchedulerProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshHideResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshPublicationResultResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshHideStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class YagaRefreshAutoOrchestrationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-14T06:00:00Z");

    private YagaRefreshSourceValidationService sourceValidationService;
    private YagaRefreshRunService runService;
    private YagaRefreshExecutionService publicationService;
    private YagaRefreshHidingExecutionService hidingService;
    private YagaRefreshRunRepository runRepository;
    private YagaRefreshAutoOrchestrationService service;
    private YagaRefreshRun run;
    private YagaRefreshJob job;

    @BeforeEach
    void setUp() {
        sourceValidationService = mock(YagaRefreshSourceValidationService.class);
        runService = mock(YagaRefreshRunService.class);
        publicationService = mock(YagaRefreshExecutionService.class);
        hidingService = mock(YagaRefreshHidingExecutionService.class);
        runRepository = mock(YagaRefreshRunRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaRefreshHidingExecutionService> hidingProvider =
                mock(ObjectProvider.class);
        when(hidingProvider.getIfAvailable()).thenReturn(hidingService);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        service = new YagaRefreshAutoOrchestrationService(
                new YagaRefreshSchedulerProperties(
                        true,
                        "0 0 3 * * *",
                        ZoneId.of("Europe/Tallinn"),
                        2
                ),
                runService,
                publicationService,
                hidingProvider,
                runRepository,
                transactionManager,
                Clock.fixed(NOW, ZoneId.of("UTC")),
                sourceValidationService
        );

        run = run(2);
        job = run.getJobs().getFirst();
        when(runRepository.findForUpdateWithJobsById(run.getId()))
                .thenReturn(Optional.of(run));
        when(runRepository.findWithJobsById(run.getId()))
                .thenReturn(Optional.of(run));
        when(runService.startScheduledAutoRun("scheduled-key", 2))
                .thenReturn(Optional.of(runResponse(run)));
    }

    @Test
    void skippedWhenRunServiceRefusesActiveProcessingRun() {
        when(runService.startScheduledAutoRun("scheduled-key", 2))
                .thenReturn(Optional.empty());

        var result = service.runScheduled("scheduled-key");

        assertThat(result.started()).isFalse();
        verify(publicationService, never()).preparePublication(any(), any());
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void concurrentScheduledInvocationSkipsWithoutStartingSecondRun()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runService.startScheduledAutoRun("scheduled-key", 2))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return Optional.empty();
                });
        var executor = Executors.newSingleThreadExecutor();

        try {
            var first = executor.submit(() ->
                    service.runScheduled("scheduled-key"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = service.runScheduled("scheduled-key");
            release.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS);

            assertThat(second.started()).isFalse();
            assertThat(firstResult.started()).isFalse();
            verify(runService, times(1))
                    .startScheduledAutoRun("scheduled-key", 2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulAutomaticPublicationAndHideCompletesRun(
            CapturedOutput output
    ) {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                    job.setNewListing(newListing(job));
                    return publicationResult(job);
                });
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenAnswer(invocation -> hidePreparation(job, true));
        when(hidingService.confirm(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.COMPLETED);
                    run.setStatus(YagaRefreshRunStatus.COMPLETED);
                    run.setCompletedAt(NOW);
                    return hideResult(job);
                });

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED);
        verify(publicationService).confirmPublication(
                eq(run.getId()), eq(job.getId()), any()
        );
        verify(hidingService).confirm(eq(run.getId()), eq(job.getId()), any());
        assertThat(output)
                .contains("Yaga AUTO refresh run created")
                .contains("runId=" + run.getId())
                .contains("selectedJobCount=2")
                .contains("Yaga AUTO refresh job started: runId=" + run.getId())
                .contains("oldListingId=" + job.getOldListing().getId())
                .contains("Yaga AUTO refresh publication preparation ready")
                .contains("Yaga AUTO refresh publication confirmed")
                .contains("newListingId=" + job.getNewListing().getId())
                .contains("externalListingId=" +
                        job.getNewListing().getExternalListingId())
                .contains("Yaga AUTO refresh hide preparation ready")
                .contains("Yaga AUTO refresh hide confirmed")
                .contains("Yaga AUTO refresh job completed")
                .contains("Yaga AUTO refresh run completed");
        assertThat(output).doesNotContain("publish-token");
        assertThat(output).doesNotContain("hide-token");
    }

    @Test
    void onDemandAutomaticPublicationAndHideUsesSelectedAccountAndBatch() {
        run.setTriggerType(YagaRefreshTriggerType.ON_DEMAND);
        run.getJobs().forEach(selected -> selected.getOldListing().setYagaAccount(run.getYagaAccount()));
        when(runService.startOnDemandAutoRun(
                1L,
                "on-demand-key",
                1
        )).thenReturn(Optional.of(runResponse(run)));
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                    job.setNewListing(newListing(job));
                    return publicationResult(job);
                });
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenAnswer(invocation -> hidePreparation(job, true));
        when(hidingService.confirm(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.COMPLETED);
                    run.setStatus(YagaRefreshRunStatus.COMPLETED);
                    run.setCompletedAt(NOW);
                    return hideResult(job);
                });

        var result = service.runOnDemand(1L, 1, "on-demand-key");

        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED);
        verify(runService).startOnDemandAutoRun(
                1L,
                "on-demand-key",
                1
        );
        verify(publicationService).confirmPublication(
                eq(run.getId()), eq(job.getId()), any()
        );
        verify(hidingService).confirm(eq(run.getId()), eq(job.getId()), any());
    }

    @Test
    void targetedOnDemandUsesNormalPublicationAndHidePipeline() {
        Long listingId = job.getOldListing().getId();
        when(runService.startOnDemandListingRun(1L, listingId, "target-key"))
                .thenReturn(runResponse(run));
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                    job.setNewListing(newListing(job));
                    return publicationResult(job);
                });
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenAnswer(invocation -> hidePreparation(job, true));
        when(hidingService.confirm(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.COMPLETED);
                    run.setStatus(YagaRefreshRunStatus.COMPLETED);
                    run.setCompletedAt(NOW);
                    return hideResult(job);
                });

        var result = service.runOnDemandListing(1L, listingId, "target-key");

        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED);
        verify(publicationService).confirmPublication(
                eq(run.getId()), eq(job.getId()), any());
        verify(hidingService).confirm(eq(run.getId()), eq(job.getId()), any());
        org.mockito.Mockito.verifyNoInteractions(sourceValidationService);
    }

    @Test
    void schedulerProcessesEnabledAutoAccountsOnlyWithPerAccountBatchSizes() {
        YagaAccountService accountService = mock(YagaAccountService.class);
        YagaAccount accountA = account(101L, "account-a", 1);
        YagaAccount accountB = account(102L, "account-b", 3);
        YagaRefreshRun runA = completedRun(accountA, 1);
        YagaRefreshRun runB = completedRun(accountB, 3);

        when(accountService.autoRefreshAccounts())
                .thenReturn(List.of(accountA, accountB));
        when(runService.startScheduledAutoRun(
                accountA,
                "scheduled-key-account-101",
                1
        )).thenReturn(Optional.of(runResponse(runA)));
        when(runService.startScheduledAutoRun(
                accountB,
                "scheduled-key-account-102",
                3
        )).thenReturn(Optional.of(runResponse(runB)));
        when(runRepository.findForUpdateWithJobsById(runA.getId()))
                .thenReturn(Optional.of(runA));
        when(runRepository.findWithJobsById(runA.getId()))
                .thenReturn(Optional.of(runA));
        when(runRepository.findForUpdateWithJobsById(runB.getId()))
                .thenReturn(Optional.of(runB));
        when(runRepository.findWithJobsById(runB.getId()))
                .thenReturn(Optional.of(runB));

        YagaRefreshAutoOrchestrationService accountAwareService =
                new YagaRefreshAutoOrchestrationService(
                        new YagaRefreshSchedulerProperties(
                                true,
                                "0 0 3 * * *",
                                ZoneId.of("Europe/Tallinn"),
                                10
                        ),
                        runService,
                        accountService,
                        publicationService,
                        hidingServiceProvider(),
                        runRepository,
                        transactionManager(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                sourceValidationService
                );

        var result = accountAwareService.runScheduled("scheduled-key");

        assertThat(result.runId()).isEqualTo(runB.getId());
        verify(accountService).autoRefreshAccounts();
        verify(runService).startScheduledAutoRun(
                accountA,
                "scheduled-key-account-101",
                1
        );
        verify(runService).startScheduledAutoRun(
                accountB,
                "scheduled-key-account-102",
                3
        );
        verify(runService, never()).startScheduledAutoRun("scheduled-key", 10);
    }

    @Test
    void invalidPublicationReadinessPreventsAutomaticConfirmationAndHide(
            CapturedOutput output
    ) {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, false));

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status())
                .isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getLastErrorCode())
                .isEqualTo("AUTO_PUBLICATION_NOT_READY");
        verify(publicationService, never())
                .confirmPublication(any(), any(), any());
        verify(hidingService, never()).prepare(any(), any());
        assertThat(output)
                .contains("Yaga AUTO refresh run stopped: runId=" + run.getId())
                .contains("jobId=" + job.getId())
                .contains("safeErrorCode=AUTO_PUBLICATION_NOT_READY")
                .contains("safeMessage=Automatic publication readiness could not be confirmed");
    }

    @Test
    void publicationFailurePreventsHiding() {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                    return publicationUnknown(job);
                });

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status())
                .isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        verify(hidingService, never()).prepare(any(), any());
        verify(hidingService, never()).confirm(any(), any(), any());
    }

    @Test
    void publicationAuthFailureStoresSpecificAutoDiagnostics(
            CapturedOutput output
    ) {
        YagaPublishingFormDiagnostics diagnostics =
                YagaPublishingFormDiagnostics.failureMetadata(
                        YagaPublishingOperationStage.RESOLVE_AUTH_STATE.name(),
                        YagaPublishingAuthException.class.getName(),
                        YagaPublishingAuthException.class.getName(),
                        "AUTH_STATE_FILE_MISSING"
                );
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenThrow(new YagaPublishingAuthException(
                        "Yaga auth state file is missing",
                        diagnostics
                ));

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status())
                .isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getLastErrorCode())
                .isEqualTo("AUTO_AUTH_STATE_FILE_MISSING");
        assertThat(run.getLastErrorCode())
                .isEqualTo("AUTO_AUTH_STATE_FILE_MISSING");
        assertThat(job.getLastSafeErrorMessage())
                .isEqualTo("Automatic Yaga refresh stopped during " +
                        "RESOLVE_AUTH_STATE");
        assertThat(output)
                .contains("Yaga AUTO refresh step failed")
                .contains("operationStage=RESOLVE_AUTH_STATE")
                .contains("safeErrorCode=AUTO_AUTH_STATE_FILE_MISSING")
                .doesNotContain("yaga-state.json");
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void openFormFailureDiagnosticPersistsOnFailedRefreshJob() {
        var snapshot = new ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaOpenFormDiagnostic.Snapshot(
                "/muuk/lisa-toode", 1, 0, 0, 0, 1, 1, 1);
        var openForm = new ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaOpenFormDiagnostic(
                snapshot, false, false, false, null, snapshot,
                ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaOpenFormDiagnostic.Reason.HIDDEN_LOGIN_MATCH);
        var diagnostics = YagaPublishingFormDiagnostics.failureMetadata(
                YagaPublishingOperationStage.OPEN_FORM.name(),
                YagaPublishingAuthException.class.getName(),
                YagaPublishingAuthException.class.getName(),
                "AUTH_SESSION_INVALID").withOpenFormDiagnostic(openForm);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenThrow(new YagaPublishingAuthException(
                        "Yaga create form is not authorized", diagnostics));

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getLastErrorCode()).isEqualTo("AUTO_AUTH_SESSION_INVALID");
        assertThat(job.getOpenFormDiagnostic()).isEqualTo(openForm.safeSummary());
        assertThat(run.getJobs().get(1).getStatus())
                .isEqualTo(YagaRefreshJobStatus.SKIPPED);
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void publicationDriveTokenFailureStoresSpecificAutoDiagnostics(
            CapturedOutput output
    ) throws Exception {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenThrow(new YagaPublishingDriveDownloadException(
                        "Failed to download product images from Google Drive",
                        tokenResponseException("invalid_grant")
                ));

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status())
                .isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getLastErrorCode())
                .isEqualTo("AUTO_GOOGLE_DRIVE_AUTH_INVALID_OR_EXPIRED");
        assertThat(run.getLastErrorCode())
                .isEqualTo("AUTO_GOOGLE_DRIVE_AUTH_INVALID_OR_EXPIRED");
        assertThat(job.getLastSafeErrorMessage())
                .isEqualTo("Automatic Yaga refresh stopped during " +
                        "DOWNLOAD_IMAGES");
        assertThat(output)
                .contains("operationStage=DOWNLOAD_IMAGES")
                .contains("rootCauseClass=" +
                        TokenResponseException.class.getName())
                .contains("safeErrorCode=" +
                        "AUTO_GOOGLE_DRIVE_AUTH_INVALID_OR_EXPIRED")
                .doesNotContain("invalid_grant");
        verify(hidingService, never()).prepare(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DELETED", "LOGIN", "UNKNOWN"})
    void hideFailurePersistsDistinctSafeDiagnosticsAndNeverRepublishes(String state, CapturedOutput output) {
        job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        job.setPublicationStatus("PUBLISHED");
        job.setNewListing(newListing(job));
        String code = state.equals("DELETED") ? "HIDE_SOURCE_INACTIVE"
                : state.equals("LOGIN") ? "HIDING_AUTH_SESSION_INVALID" : "HIDE_TARGET_UNVERIFIED";
        var details = java.util.Map.of("operationStage", "INSPECT_HIDE_TARGET", "pageState", state,
                "safeErrorCode", code, "currentUrl", "https://www.yaga.ee/login?token=must-not-log");
        RuntimeException failure = state.equals("LOGIN")
                ? new YagaHidingAuthException("login", details)
                : new ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingPreconditionException("inactive/unknown", details);
        when(hidingService.prepare(run.getId(), job.getId())).thenThrow(failure);
        service.runScheduled("scheduled-key");
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getPublicationStatus()).isEqualTo("PUBLISHED");
        assertThat(job.getNewListing()).isNotNull();
        assertThat(job.getLastErrorCode()).isEqualTo("AUTO_" + code);
        assertThat(job.getLastSafeErrorMessage()).contains("INSPECT_HIDE_TARGET", "pageState=" + state)
                .doesNotContain("must-not-log");
        assertThat(output).contains("operationStage=INSPECT_HIDE_TARGET", "safeErrorCode=AUTO_" + code)
                .doesNotContain("must-not-log");
        verify(publicationService, never()).preparePublication(any(), any());
        verify(publicationService, never()).confirmPublication(any(), any(), any());
        verify(hidingService, never()).confirm(any(), any(), any());
    }

    @Test
    void sourceValidationFailureStopsRunBeforePublicationAndSkipsSuccessors() {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenThrow(new ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException());

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getPublicationConfirmStartedAt()).isNull();
        assertThat(job.getNewListing()).isNull();
        assertThat(job.getLastSafeErrorMessage()).contains("SOURCE_NOT_ACTIVE");
        assertThat(run.getJobs().get(1).getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
        verify(publicationService, never()).confirmPublication(any(), any(), any());
        verify(hidingService, never()).prepare(any(), any());
        org.mockito.Mockito.verifyNoInteractions(sourceValidationService);
    }

    @Test
    void invalidRefreshStateStoresStateDiagnostics(CapturedOutput output) {
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenThrow(new YagaRefreshInvalidStateException(
                        "Yaga refresh job cannot prepare publication: " +
                                "currentJobStatus=FAILED " +
                                "currentPublicationStatus=null " +
                                "currentHideStatus=NOT_STARTED " +
                                "expectedJobStatus=SELECTED"
                ));

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status())
                .isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getLastErrorCode())
                .isEqualTo("AUTO_REFRESH_INVALID_STATE");
        assertThat(job.getLastSafeErrorMessage())
                .contains("currentJobStatus=FAILED")
                .contains("expectedJobStatus=SELECTED");
        assertThat(run.getLastErrorCode())
                .isEqualTo("AUTO_REFRESH_INVALID_STATE");
        assertThat(output)
                .contains("Yaga AUTO refresh step failed")
                .contains("safeErrorCode=AUTO_REFRESH_INVALID_STATE");
        verify(hidingService, never()).prepare(any(), any());
    }

    @Test
    void alreadyPublicationConfirmedJobResumesWithHidingOnly() {
        job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
        job.setNewListing(newListing(job));
        job.setNewExternalListingId(job.getNewListing().getExternalListingId());
        job.setNewShopSlug(job.getNewListing().getShopSlug());
        job.setNewProductSlug(job.getNewListing().getProductSlug());
        job.setNewProductUrl(job.getNewListing().getExternalUrl());
        job.setPublicationStatus(YagaPublicationStatus.PUBLISHED.name());
        job.setPublicationConfirmedAt(NOW);
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenAnswer(invocation -> hidePreparation(job, true));
        when(hidingService.confirm(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.COMPLETED);
                    run.setStatus(YagaRefreshRunStatus.COMPLETED);
                    run.setCompletedAt(NOW);
                    return hideResult(job);
                });

        var result = service.runScheduled("scheduled-key");

        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED);
        verify(publicationService, never()).preparePublication(any(), any());
        verify(publicationService, never())
                .confirmPublication(any(), any(), any());
        verify(hidingService).prepare(run.getId(), job.getId());
        verify(hidingService).confirm(eq(run.getId()), eq(job.getId()), any());
    }

    @Test
    void unsafeFirstJobStopsLaterJobs() {
        YagaRefreshJob later = run.getJobs().get(1);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, false));

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(later.getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
        assertThat(later.getCompletedAt()).isEqualTo(NOW);
        verify(publicationService, never())
                .preparePublication(run.getId(), later.getId());
    }

    @Test
    void unknownPublicationSkipsOnlyUnexecutedLaterJob() {
        YagaRefreshJob later = run.getJobs().get(1);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.RESULT_UNKNOWN);
                    return publicationUnknown(job);
                });

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.RESULT_UNKNOWN);
        assertThat(later.getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
        verify(publicationService, never()).preparePublication(run.getId(), later.getId());
    }

    @Test
    void laterJobWithExecutionEvidenceIsNeverSkipped() {
        YagaRefreshJob later = run.getJobs().get(1);
        later.setPublicationPreparedAt(NOW);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, false));

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(later.getStatus()).isEqualTo(YagaRefreshJobStatus.SELECTED);
    }

    @Test
    void stoppingSecondJobLeavesCompletedFirstJobUnchanged() {
        YagaRefreshJob second = run.getJobs().get(1);
        job.setStatus(YagaRefreshJobStatus.COMPLETED);
        job.setCompletedAt(NOW.minusSeconds(60));
        when(publicationService.preparePublication(run.getId(), second.getId()))
                .thenReturn(publicationPreparation(second, false));

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.COMPLETED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(second.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
    }

    @Test
    void hidePreparationFailureSkipsLaterJobWithoutChangingConfirmedPublication() {
        YagaRefreshJob later = run.getJobs().get(1);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenReturn(publicationPreparation(job, true));
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                    job.setPublicationStatus(YagaPublicationStatus.PUBLISHED.name());
                    job.setNewListing(newListing(job));
                    return publicationResult(job);
                });
        when(hidingService.prepare(run.getId(), job.getId()))
                .thenAnswer(invocation -> hidePreparation(job, false));

        service.runScheduled("scheduled-key");

        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(job.getPublicationStatus()).isEqualTo("PUBLISHED");
        assertThat(later.getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
        verify(hidingService, never()).prepare(run.getId(), later.getId());
    }

    private void batchRun(int count) {
        run = run(count);
        run.setTriggerType(YagaRefreshTriggerType.ON_DEMAND);
        job = run.getJobs().getFirst();
        for (YagaRefreshJob selected : run.getJobs()) {
            selected.getOldListing().setYagaAccount(run.getYagaAccount());
        }
        when(runRepository.findForUpdateWithJobsById(run.getId())).thenReturn(Optional.of(run));
        when(runRepository.findWithJobsById(run.getId())).thenReturn(Optional.of(run));
        when(runService.startOnDemandAutoRun(1L, "batch-key", count))
                .thenAnswer(invocation -> Optional.of(runResponse(run)));
    }

    private YagaRefreshSourceValidationService.Source source(YagaRefreshJob selected) {
        return new YagaRefreshSourceValidationService.Source(selected.getOldExternalListingId(),
                selected.getOldShopSlug(), selected.getOldProductSlug(), selected.getOldPublicUrl());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"100", "110", "001", "111"})
    void inactiveBatchJobsSkipAndRemainingJobsCompleteIncludingReplay(String inactive) {
        batchRun(inactive.length());
        for (int i = 0; i < inactive.length(); i++) {
            YagaRefreshJob selected = run.getJobs().get(i);
            if (inactive.charAt(i) == '1') {
                org.mockito.Mockito.doThrow(new ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException())
                        .when(sourceValidationService).requireActive(source(selected));
            } else {
                when(publicationService.preparePublication(run.getId(), selected.getId()))
                        .thenReturn(publicationPreparation(selected, true));
                when(publicationService.confirmPublication(eq(run.getId()), eq(selected.getId()), any()))
                        .thenAnswer(invocation -> {
                            selected.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                            selected.setNewListing(newListing(selected));
                            return publicationResult(selected);
                        });
                when(hidingService.prepare(run.getId(), selected.getId()))
                        .thenAnswer(invocation -> hidePreparation(selected, true));
                when(hidingService.confirm(eq(run.getId()), eq(selected.getId()), any()))
                        .thenAnswer(invocation -> {
                            selected.setStatus(YagaRefreshJobStatus.COMPLETED);
                            if (YagaRefreshSequencingGuard.currentJob(run).isEmpty()) {
                                run.setStatus(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
                                run.setCompletedAt(NOW);
                            }
                            return hideResult(selected);
                        });
            }
        }

        var result = service.runOnDemand(1L, inactive.length(), "batch-key");
        assertThat(result.status()).isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
        for (int i = 0; i < inactive.length(); i++) {
            YagaRefreshJob selected = run.getJobs().get(i);
            assertThat(selected.getStatus()).isEqualTo(inactive.charAt(i) == '1'
                    ? YagaRefreshJobStatus.SKIPPED : YagaRefreshJobStatus.COMPLETED);
            if (inactive.charAt(i) == '1') {
                assertThat(selected.getLastErrorCode()).isEqualTo("SOURCE_NOT_ACTIVE");
                assertThat(selected.getCompletedAt()).isEqualTo(NOW);
                assertThat(selected.getAttemptCount()).isZero();
                assertThat(selected.getPublicationPreparationId()).isNull();
                assertThat(selected.getPublicationConfirmStartedAt()).isNull();
                assertThat(selected.getNewListing()).isNull();
                assertThat(selected.getHideStatus()).isEqualTo(YagaRefreshHideStatus.NOT_STARTED);
                verify(publicationService, never()).preparePublication(run.getId(), selected.getId());
                verify(publicationService, never()).confirmPublication(eq(run.getId()), eq(selected.getId()), any());
                verify(hidingService, never()).prepare(run.getId(), selected.getId());
                verify(hidingService, never()).confirm(eq(run.getId()), eq(selected.getId()), any());
            }
        }
        org.mockito.Mockito.clearInvocations(sourceValidationService, publicationService, hidingService);
        service.runOnDemand(1L, inactive.length(), "batch-key");
        org.mockito.Mockito.verifyNoInteractions(sourceValidationService, publicationService, hidingService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SOURCE_NOT_ACTIVE", "SOURCE_VALIDATION_UNAVAILABLE", "SOURCE_IDENTITY_UNVERIFIED"})
    void ambiguousOrFailedPreflightStopsBatch(String code) {
        batchRun(2);
        org.mockito.Mockito.doThrow(new YagaRefreshInvalidStateException(code))
                .when(sourceValidationService).requireActive(source(job));
        service.runOnDemand(1L, 2, "batch-key");
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        assertThat(run.getStatus()).isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        org.mockito.Mockito.verifyNoInteractions(publicationService, hidingService);
    }

    @Test
    void preexistingExecutionEvidencePreventsPreflightAndSkip() {
        batchRun(2);
        job.setPublicationPreparedAt(NOW);
        service.runOnDemand(1L, 2, "batch-key");
        assertThat(job.getStatus()).isEqualTo(YagaRefreshJobStatus.FAILED);
        org.mockito.Mockito.verifyNoInteractions(sourceValidationService, publicationService, hidingService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"identity", "evidence", "order", "terminal", "version"})
    void changedStateDuringInactiveReadCannotBeSkipped(String change) {
        batchRun(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            switch (change) {
                case "identity" -> job.setOldExternalListingId("changed");
                case "evidence" -> job.setPublicationConfirmStartedAt(NOW);
                case "order" -> run.getJobs().get(1).setSelectionOrder(-1);
                case "terminal" -> run.setStatus(YagaRefreshRunStatus.CANCELLED);
                case "version" -> job.setVersion(1L);
            }
            throw new ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException();
        }).when(sourceValidationService).requireActive(source(job));
        service.runOnDemand(1L, 2, "batch-key");
        assertThat(job.getStatus()).isNotEqualTo(YagaRefreshJobStatus.SKIPPED);
        org.mockito.Mockito.verifyNoInteractions(publicationService, hidingService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"prepare", "confirm", "hide", "publicationUnknown", "hideUnknown"})
    void failuresAfterPreflightRetainStopAndRecoveryBehavior(String stage) {
        batchRun(2);
        when(publicationService.preparePublication(run.getId(), job.getId()))
                .thenAnswer(invocation -> {
                    if (stage.equals("prepare")) {
                        throw new ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException();
                    }
                    job.setStatus(YagaRefreshJobStatus.PUBLISHING);
                    job.setPublicationPreparationId(UUID.randomUUID());
                    return publicationPreparation(job, true);
                });
        when(publicationService.confirmPublication(eq(run.getId()), eq(job.getId()), any()))
                .thenAnswer(invocation -> {
                    if (stage.equals("confirm")) {
                        throw new ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshSourceInactiveException();
                    }
                    if (stage.equals("publicationUnknown")) {
                        job.setPublicationConfirmStartedAt(NOW);
                        throw new IllegalStateException("unknown publication");
                    }
                    job.setStatus(YagaRefreshJobStatus.NEW_LISTING_CONFIRMED);
                    job.setPublicationStatus("PUBLISHED");
                    job.setNewListing(newListing(job));
                    return publicationResult(job);
                });
        when(hidingService.prepare(run.getId(), job.getId())).thenAnswer(invocation -> {
            if (stage.equals("hideUnknown")) {
                job.setStatus(YagaRefreshJobStatus.HIDING_OLD);
                job.setHideConfirmStartedAt(NOW);
            }
            throw new IllegalStateException("hide failed");
        });
        service.runOnDemand(1L, 2, "batch-key");
        assertThat(job.getStatus()).isEqualTo(stage.endsWith("Unknown")
                ? YagaRefreshJobStatus.RESULT_UNKNOWN : YagaRefreshJobStatus.FAILED);
        assertThat(run.getJobs().get(1).getStatus()).isEqualTo(YagaRefreshJobStatus.SKIPPED);
        assertThat(run.getStatus()).isEqualTo(YagaRefreshRunStatus.COMPLETED_WITH_ERRORS);
        verify(publicationService, never()).preparePublication(run.getId(), run.getJobs().get(1).getId());
        if (stage.startsWith("hide")) {
            assertThat(job.getNewListing()).isNotNull();
            assertThat(job.getPublicationStatus()).isEqualTo("PUBLISHED");
        }
    }

    private YagaRefreshPublicationPreparationResponse publicationPreparation(
            YagaRefreshJob job,
            boolean ready
    ) {
        UUID preparationId = UUID.randomUUID();
        return new YagaRefreshPublicationPreparationResponse(
                run.getId(),
                job.getId(),
                job.getProduct().getId(),
                job.getOldListing().getId(),
                YagaRefreshJobStatus.PUBLISHING,
                ready ? preparationId : null,
                ready ? "AWAITING_CONFIRMATION" : null,
                ready ? "publish-token" : null,
                NOW.plusSeconds(600),
                new YagaPublishReadinessResponse(
                        preparationId,
                        YagaPublicationStatus.AWAITING_CONFIRMATION,
                        "https://www.yaga.ee/muuk/lisa-toode",
                        ready,
                        ready ? 1 : 0,
                        ready ? 1 : 0,
                        ready ? 1 : 0,
                        null,
                        null,
                        null,
                        ready,
                        NOW
                )
        );
    }

    private YagaRefreshHidePreparationResponse hidePreparation(
            YagaRefreshJob job,
            boolean ready
    ) {
        UUID preparationId = UUID.randomUUID();
        return new YagaRefreshHidePreparationResponse(
                run.getId(),
                job.getId(),
                job.getProduct().getId(),
                job.getOldListing().getId(),
                job.getNewListing().getId(),
                YagaRefreshJobStatus.HIDING_OLD,
                ready ? preparationId : null,
                ready
                        ? YagaRefreshHideStatus.AWAITING_CONFIRMATION
                        : YagaRefreshHideStatus.TARGET_INVALID,
                ready ? "hide-token" : null,
                NOW.plusSeconds(600),
                new YagaRefreshHideReadinessResponse(
                        preparationId,
                        YagaHidingStatus.AWAITING_CONFIRMATION,
                        ready,
                        ready ? 1 : 0,
                        ready ? 1 : 0,
                        ready ? 1 : 0,
                        ready,
                        NOW,
                        "INSPECT_HIDE_TARGET",
                        "www.yaga.ee",
                        "/nik-ar/toode/old",
                        "nik-ar",
                        "old",
                        true
                )
        );
    }

    private YagaRefreshPublicationResultResponse publicationResult(
            YagaRefreshJob job
    ) {
        return new YagaRefreshPublicationResultResponse(
                run.getId(),
                job.getId(),
                YagaRefreshRunStatus.PROCESSING,
                YagaRefreshJobStatus.NEW_LISTING_CONFIRMED,
                null,
                job.getOldListing().getId(),
                job.getNewListing().getId(),
                job.getNewListing().getExternalListingId(),
                job.getNewListing().getShopSlug(),
                job.getNewListing().getProductSlug(),
                job.getNewListing().getExternalUrl(),
                NOW,
                null,
                null
        );
    }

    private YagaRefreshPublicationResultResponse publicationUnknown(
            YagaRefreshJob job
    ) {
        return new YagaRefreshPublicationResultResponse(
                run.getId(),
                job.getId(),
                YagaRefreshRunStatus.PROCESSING,
                YagaRefreshJobStatus.RESULT_UNKNOWN,
                null,
                job.getOldListing().getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                "PUBLICATION_RESULT_UNKNOWN",
                "Yaga publication result could not be confirmed"
        );
    }

    private YagaRefreshHideResultResponse hideResult(YagaRefreshJob job) {
        return new YagaRefreshHideResultResponse(
                run.getId(),
                job.getId(),
                YagaRefreshRunStatus.COMPLETED,
                YagaRefreshJobStatus.COMPLETED,
                null,
                YagaRefreshHideStatus.HIDDEN,
                job.getOldListing().getId(),
                job.getNewListing().getId(),
                NOW,
                null,
                null
        );
    }

    private YagaRefreshRun run(int jobCount) {
        YagaRefreshRun run = new YagaRefreshRun(
                YagaRefreshTriggerType.SCHEDULED,
                YagaRefreshRunMode.AUTO,
                jobCount,
                "scheduled-key",
                NOW
        );
        run.setId(UUID.randomUUID());
        run.setStatus(YagaRefreshRunStatus.PROCESSING);
        for (int i = 0; i < jobCount; i++) {
            Product product = new Product("SKU-" + i, "Title " + i);
            product.setId((long) i + 1);
            MarketplaceListing listing = listing(
                    (long) i + 10,
                    product,
                    "old-" + i,
                    "old-" + i
            );
            YagaRefreshJob job = new YagaRefreshJob(
                    product,
                    listing,
                    listing.getExternalListingId(),
                    listing.getShopSlug(),
                    listing.getProductSlug(),
                    listing.getExternalUrl(),
                    product.getTitle(),
                    NOW.minusSeconds(1000L + i),
                    NOW.minusSeconds(1000L + i),
                    1,
                    1,
                    i,
                    NOW
            );
            job.setId(UUID.randomUUID());
            run.addJob(job);
        }
        run.setSelectedJobCount(jobCount);
        return run;
    }

    private YagaRefreshRun completedRun(
            YagaAccount account,
            int requestedBatchSize
    ) {
        YagaRefreshRun run = new YagaRefreshRun(
                account,
                YagaRefreshTriggerType.SCHEDULED,
                YagaRefreshRunMode.AUTO,
                requestedBatchSize,
                "scheduled-key",
                NOW
        );
        run.setId(UUID.randomUUID());
        run.setStatus(YagaRefreshRunStatus.COMPLETED);
        run.setCompletedAt(NOW);
        run.setSelectedJobCount(0);
        return run;
    }

    private YagaAccount account(Long id, String shopSlug, int batchSize) {
        YagaAccount account = new YagaAccount(
                "Account " + shopSlug,
                shopSlug,
                "../playwright/.auth/" + shopSlug + ".json",
                batchSize
        );
        account.setId(id);
        return account;
    }

    private ObjectProvider<YagaRefreshHidingExecutionService>
    hidingServiceProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<YagaRefreshHidingExecutionService> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(hidingService);
        return provider;
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }

    private MarketplaceListing newListing(YagaRefreshJob job) {
        return listing(
                job.getOldListing().getId() + 100,
                job.getProduct(),
                "new-" + job.getSelectionOrder(),
                "new-" + job.getSelectionOrder()
        );
    }

    private MarketplaceListing listing(
            Long id,
            Product product,
            String externalId,
            String slug
    ) {
        MarketplaceListing listing = new MarketplaceListing(
                product,
                Marketplace.YAGA,
                externalId,
                "https://www.yaga.ee/nik-ar/toode/" + slug
        );
        listing.setId(id);
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(slug);
        return listing;
    }

    private YagaRefreshRunResponse runResponse(YagaRefreshRun run) {
        return new YagaRefreshRunResponse(
                run.getId(),
                run.getTriggerType(),
                run.getMode(),
                run.getStatus(),
                run.getRequestedBatchSize(),
                run.getSelectedJobCount(),
                run.getCreatedAt(),
                run.getCompletedAt(),
                List.of()
        );
    }

    private TokenResponseException tokenResponseException(String error)
            throws Exception {

        TokenErrorResponse response = new TokenErrorResponse()
                .setError(error);

        Constructor<TokenResponseException> constructor =
                TokenResponseException.class.getDeclaredConstructor(
                        HttpResponseException.Builder.class,
                        TokenErrorResponse.class
                );
        constructor.setAccessible(true);

        return constructor.newInstance(
                new HttpResponseException.Builder(
                        400,
                        "Bad Request",
                        new HttpHeaders()
                ),
                response
        );
    }
}
