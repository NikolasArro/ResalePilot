package ee.nikolas.resalepilot.workflow.yaga.hiding;

import ee.nikolas.resalepilot.workflow.yaga.common.YagaConfirmationTokenService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.automation.YagaHidingBrowserAutomation;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideResult;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;

import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHideReadinessResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPreparationAlreadyRunningException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationConfirmDisabledException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationExpiredException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationForbiddenException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(
        name = "yaga.hiding.enabled",
        havingValue = "true"
)
public class YagaHidingSessionManager {

    private final YagaHidingPreparationService preparationService;
    private final YagaHidingBrowserAutomation browserAutomation;
    private final YagaConfirmationTokenService tokenService;
    private final YagaHidingProperties properties;
    private final YagaPageDataClient pageDataClient;
    private final ScheduledExecutorService expiryExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<UUID, Session> sessions =
            new ConcurrentHashMap<>();
    private final AtomicReference<Session> activeSession =
            new AtomicReference<>();

    public YagaHidingSessionManager(
            YagaHidingPreparationService preparationService,
            YagaHidingBrowserAutomation browserAutomation,
            YagaConfirmationTokenService tokenService,
            YagaHidingProperties properties,
            YagaPageDataClient pageDataClient
    ) {
        this.preparationService = preparationService;
        this.browserAutomation = browserAutomation;
        this.tokenService = tokenService;
        this.properties = properties;
        this.pageDataClient = pageDataClient;
    }

    public YagaHidePreparationResponse prepare(Long oldListingId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getConfirmationTtl());
        String token = tokenService.generateToken();
        Session session = new Session(
                id,
                oldListingId,
                now,
                expiresAt,
                tokenService.hashToken(token)
        );

        if (!activeSession.compareAndSet(null, session)) {
            throw new YagaPreparationAlreadyRunningException();
        }
        sessions.put(id, session);
        session.expiryFuture = expiryExecutor.schedule(
                () -> expire(id),
                properties.getConfirmationTtl().toMillis(),
                TimeUnit.MILLISECONDS
        );

        try {
            return submit(session, () -> {
                try {
                    YagaHidingDraftData draft =
                            preparationService.loadAndVerifyDraft(
                                    oldListingId
                            );
                    YagaHidingPreparedBrowserSession browserSession =
                            browserAutomation.prepareSession(draft);
                    session.browserSession = browserSession;
                    YagaHideControlInspection inspection =
                            browserAutomation.inspectHideControl(
                                    browserSession
                            );
                    preparationService.validateReadyInspection(
                            draft,
                            inspection
                    );

                    session.draft = draft;
                    session.currentUrl = inspection.currentUrl();
                    session.status =
                            YagaHidingStatus.AWAITING_CONFIRMATION;

                    return preparationResponse(
                            session,
                            draft,
                            inspection,
                            token
                    );

                } catch (RuntimeException exception) {
                    failAndClose(session, exception);
                    throw exception;
                }
            });
        } catch (RuntimeException exception) {
            if (session.isTerminal()) {
                activeSession.compareAndSet(session, null);
            }
            throw exception;
        }
    }

    public YagaHidePreparationStatusResponse status(UUID id) {
        return requireSession(id).statusResponse();
    }

    public YagaHideReadinessResponse readiness(UUID id) {
        Session session = requireSession(id);
        return submit(session, () -> {
            expireIfNeeded(session);
            if (session.status != YagaHidingStatus.AWAITING_CONFIRMATION ||
                    session.browserSession == null) {
                return new YagaHideReadinessResponse(
                        session.id,
                        session.status,
                        session.currentUrl,
                        false,
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        false,
                        Instant.now()
                );
            }

            YagaHideControlInspection inspection =
                    browserAutomation.inspectHideControl(
                            session.browserSession
                    );
            boolean targetStillValid =
                    inspection.readyForConfirmation();
            session.currentUrl = inspection.currentUrl();
            return readinessResponse(session, inspection, targetStillValid);
        });
    }

    public YagaHideConfirmResponse confirm(
            UUID id,
            YagaHideConfirmRequest request
    ) {
        return confirm(id, request, true);
    }

    public YagaHideConfirmResponse confirmForRefresh(
            UUID id,
            YagaHideConfirmRequest request
    ) {
        return confirm(id, request, false);
    }

    private YagaHideConfirmResponse confirm(
            UUID id,
            YagaHideConfirmRequest request,
            boolean synchronizeListings
    ) {
        if (!properties.isConfirmEnabled()) {
            throw new YagaPublicationConfirmDisabledException();
        }

        Session session = requireSession(id);
        return submit(session, () -> {
            ensureAwaiting(session);
            ensureNotExpired(session);
            ensureConfirmation(request, session);

            try {
                YagaHidingDraftData verifiedDraft =
                        preparationService.loadAndVerifyDraft(
                                session.oldListingId
                        );
                if (!verifiedDraft.equals(session.draft)) {
                    throw new YagaPublicationInvalidStateException(
                            "Yaga hiding target changed before confirmation"
                    );
                }
                YagaHideControlInspection inspection =
                        browserAutomation.inspectHideControl(
                                session.browserSession
                        );
                preparationService.validateReadyInspection(
                        session.draft,
                        inspection
                );
                if (!inspection.readyForConfirmation()) {
                    throw new YagaPublishingFormException(
                            "Yaga hide button is not ready for confirmation"
                    );
                }
                session.currentUrl = inspection.currentUrl();

            } catch (RuntimeException exception) {
                session.lastSafeErrorMessage = exception.getMessage();
                throw exception;
            }

            session.status = YagaHidingStatus.HIDING;
            session.tokenHash = null;

            YagaHideResult result;
            try {
                result = browserAutomation.hidePreparedSession(
                        session.browserSession
                );
            } catch (RuntimeException exception) {
                session.status = YagaHidingStatus.HIDE_RESULT_UNKNOWN;
                session.lastSafeErrorMessage =
                        "Yaga hide result could not be confirmed";
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
                return confirmResponse(session);
            }
            session.currentUrl = result.currentUrl();

            if (!result.clickPerformed()) {
                session.status = YagaHidingStatus.HIDE_RESULT_UNKNOWN;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
                return confirmResponse(session);
            }

            reconcileHiddenResult(session, synchronizeListings);
            closeBrowser(session);
            activeSession.compareAndSet(session, null);
            return confirmResponse(session);
        });
    }

    public YagaHidePreparationStatusResponse cancel(UUID id) {
        Session session = requireSession(id);
        return submit(session, () -> {
            if (session.status == YagaHidingStatus.HIDING) {
                throw new YagaPublicationInvalidStateException(
                        "Yaga hiding cannot be cancelled after click"
                );
            }
            if (!session.isTerminal()) {
                session.status = YagaHidingStatus.CANCELLED;
                session.tokenHash = null;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
            }
            return session.statusResponse();
        });
    }

    private void reconcileHiddenResult(
            Session session,
            boolean synchronizeListings
    ) {
        YagaImportedProductData data;
        try {
            data = pollHiddenProductData(session.draft);
        } catch (RuntimeException exception) {
            session.status = YagaHidingStatus.HIDE_RESULT_UNKNOWN;
            session.lastSafeErrorMessage =
                    "Yaga hidden state could not be confirmed";
            return;
        }

        if (synchronizeListings
                ? !preparationService.isHiddenYagaStatus(data)
                : !isStrictRefreshHiddenStatus(data)) {
            session.status = YagaHidingStatus.HIDE_RESULT_UNKNOWN;
            session.lastSafeErrorMessage =
                    "Yaga hidden state could not be confirmed";
            return;
        }

        if (!synchronizeListings) {
            session.hiddenAt = data.hiddenAt() == null
                    ? Instant.now()
                    : data.hiddenAt();
            session.status = YagaHidingStatus.HIDDEN;
            return;
        }

        try {
            session.hiddenAt = preparationService.markOldHiddenAndNewCurrent(
                    session.draft,
                    data.hiddenAt()
            );
            session.status = YagaHidingStatus.HIDDEN;
        } catch (RuntimeException exception) {
            session.status = YagaHidingStatus.HIDDEN_DB_SYNC_FAILED;
            session.lastSafeErrorMessage =
                    "Yaga listing was hidden but DB sync failed";
        }
    }

    private boolean isStrictRefreshHiddenStatus(
            YagaImportedProductData data
    ) {
        return data != null && data.deletedAt() == null &&
                ("hidden".equals(data.status()) ||
                        "not-visible".equals(data.status()));
    }

    private YagaImportedProductData pollHiddenProductData(
            YagaHidingDraftData draft
    ) {
        Instant deadline = Instant.now()
                .plus(properties.getHideDataPollTimeout());
        RuntimeException lastException = null;

        while (!Instant.now().isAfter(deadline)) {
            try {
                YagaImportedProductData data =
                        pageDataClient.getProduct(
                                draft.oldExternalUrl()
                        );
                if (matchesOldListing(draft, data) &&
                        preparationService.isHiddenYagaStatus(data)) {
                    return data;
                }
            } catch (RuntimeException exception) {
                lastException = exception;
            }
            sleepPollInterval();
        }

        if (lastException != null) {
            throw new YagaPublishingFormException(
                    "Yaga hidden page data was not available in time",
                    lastException
            );
        }
        throw new YagaPublishingFormException(
                "Yaga hidden state was not confirmed in time"
        );
    }

    private boolean matchesOldListing(
            YagaHidingDraftData draft,
            YagaImportedProductData data
    ) {
        if (draft == null || data == null) {
            return false;
        }
        return draft.shopSlug().equals(data.shopSlug()) &&
                draft.oldProductSlug().equals(data.productSlug()) &&
                draft.oldExternalListingId()
                        .equals(data.externalId().toString());
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(
                    properties.getHideDataPollInterval().toMillis()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new YagaPublishingFormException(
                    "Yaga hidden page polling was interrupted",
                    exception
            );
        }
    }

    private void ensureConfirmation(
            YagaHideConfirmRequest request,
            Session session
    ) {
        if (request == null ||
                !"HIDE".equals(request.confirmationPhrase()) ||
                session.tokenHash == null ||
                !tokenService.matches(
                        request.confirmationToken(),
                        session.tokenHash
                )) {
            throw new YagaPublicationForbiddenException();
        }
    }

    private void ensureAwaiting(Session session) {
        if (session.status == YagaHidingStatus.EXPIRED) {
            throw new YagaPublicationExpiredException();
        }
        if (session.status != YagaHidingStatus.AWAITING_CONFIRMATION) {
            throw new YagaPublicationInvalidStateException(
                    "Yaga hiding preparation cannot be confirmed from status: " +
                            session.status
            );
        }
    }

    private void ensureNotExpired(Session session) {
        if (Instant.now().isAfter(session.expiresAt)) {
            session.status = YagaHidingStatus.EXPIRED;
            session.tokenHash = null;
            closeBrowser(session);
            activeSession.compareAndSet(session, null);
            throw new YagaPublicationExpiredException();
        }
    }

    private void expireIfNeeded(Session session) {
        if (Instant.now().isAfter(session.expiresAt) &&
                !session.isTerminal()) {
            session.status = YagaHidingStatus.EXPIRED;
            session.tokenHash = null;
            closeBrowser(session);
            activeSession.compareAndSet(session, null);
        }
    }

    private void expire(UUID id) {
        Session session = activeSession.get();
        if (session == null || !session.id.equals(id)) {
            return;
        }
        submit(session, () -> {
            if (!session.isTerminal() &&
                    session.status != YagaHidingStatus.HIDING) {
                session.status = YagaHidingStatus.EXPIRED;
                session.tokenHash = null;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
            }
            return null;
        });
    }

    private void failAndClose(
            Session session,
            RuntimeException exception
    ) {
        session.status = YagaHidingStatus.FAILED;
        session.tokenHash = null;
        session.lastSafeErrorMessage = exception.getMessage();
        closeBrowser(session);
        activeSession.compareAndSet(session, null);
    }

    private YagaHidePreparationResponse preparationResponse(
            Session session,
            YagaHidingDraftData draft,
            YagaHideControlInspection inspection,
            String token
    ) {
        return new YagaHidePreparationResponse(
                session.id,
                draft.oldListingId(),
                draft.newListingId(),
                draft.productId(),
                draft.oldExternalListingId(),
                draft.oldProductSlug(),
                draft.newExternalListingId(),
                draft.newProductSlug(),
                inspection.currentUrl(),
                inspection.candidateCount(),
                inspection.visibleCandidateCount(),
                inspection.enabledCandidateCount(),
                inspection.controlText(),
                inspection.accessibleName(),
                inspection.tagName(),
                inspection.typeAttribute(),
                inspection.readyForConfirmation(),
                token,
                session.expiresAt,
                session.status.name()
        );
    }

    private YagaHideReadinessResponse readinessResponse(
            Session session,
            YagaHideControlInspection inspection,
            boolean targetStillValid
    ) {
        return new YagaHideReadinessResponse(
                session.id,
                session.status,
                inspection.currentUrl(),
                targetStillValid,
                inspection.candidateCount(),
                inspection.visibleCandidateCount(),
                inspection.enabledCandidateCount(),
                inspection.controlText(),
                inspection.tagName(),
                inspection.typeAttribute(),
                inspection.readyForConfirmation(),
                inspection.inspectedAt()
        );
    }

    private YagaHideConfirmResponse confirmResponse(Session session) {
        return new YagaHideConfirmResponse(
                session.id,
                session.oldListingId,
                session.draft == null ? null : session.draft.newListingId(),
                session.status == YagaHidingStatus.HIDDEN ||
                        session.status ==
                                YagaHidingStatus.HIDDEN_DB_SYNC_FAILED,
                session.draft == null ? null : session.draft.oldExternalUrl(),
                session.hiddenAt,
                session.status
        );
    }

    private Session requireSession(UUID id) {
        Session session = activeSession.get();
        if (session == null || !session.id.equals(id)) {
            session = sessions.get(id);
        }
        if (session == null) {
            throw new YagaPublicationPreparationNotFoundException(id);
        }
        return session;
    }

    private <T> T submit(Session session, Callable<T> callable) {
        try {
            return session.executor.submit(callable).get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void closeBrowser(Session session) {
        if (session.browserSession != null) {
            browserAutomation.closeSession(session.browserSession);
            session.browserSession = null;
        }
        if (session.expiryFuture != null) {
            session.expiryFuture.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        Session session = activeSession.get();
        if (session != null) {
            submit(session, () -> {
                closeBrowser(session);
                session.executor.shutdownNow();
                return null;
            });
        }
        sessions.values().forEach(storedSession -> {
            if (storedSession != activeSession.get()) {
                storedSession.executor.shutdownNow();
            }
        });
        expiryExecutor.shutdownNow();
    }

    private final class Session {

        private final UUID id;
        private final Long oldListingId;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final ExecutorService executor =
                Executors.newSingleThreadExecutor();
        private volatile YagaHidingStatus status =
                YagaHidingStatus.PREPARING;
        private volatile byte[] tokenHash;
        private volatile YagaHidingDraftData draft;
        private volatile YagaHidingPreparedBrowserSession browserSession;
        private volatile String currentUrl;
        private volatile Instant hiddenAt;
        private volatile String lastSafeErrorMessage;
        private volatile ScheduledFuture<?> expiryFuture;

        private Session(
                UUID id,
                Long oldListingId,
                Instant createdAt,
                Instant expiresAt,
                byte[] tokenHash
        ) {
            this.id = id;
            this.oldListingId = oldListingId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.tokenHash = tokenHash;
        }

        private boolean isTerminal() {
            return status == YagaHidingStatus.HIDDEN ||
                    status == YagaHidingStatus.HIDDEN_DB_SYNC_FAILED ||
                    status == YagaHidingStatus.HIDE_RESULT_UNKNOWN ||
                    status == YagaHidingStatus.FAILED ||
                    status == YagaHidingStatus.CANCELLED ||
                    status == YagaHidingStatus.EXPIRED;
        }

        private YagaHidePreparationStatusResponse statusResponse() {
            return new YagaHidePreparationStatusResponse(
                    id,
                    oldListingId,
                    draft == null ? null : draft.newListingId(),
                    status,
                    createdAt,
                    expiresAt,
                    currentUrl,
                    draft == null ? null : draft.oldExternalUrl(),
                    lastSafeErrorMessage
            );
        }
    }
}
