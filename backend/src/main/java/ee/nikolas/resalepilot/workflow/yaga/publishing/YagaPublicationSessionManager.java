package ee.nikolas.resalepilot.workflow.yaga.publishing;

import ee.nikolas.resalepilot.integration.drive.model.DownloadedDriveFile;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPreparationAlreadyRunningException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationConfirmDisabledException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationExpiredException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationForbiddenException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.common.exception.YagaPublicationPreparationNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.common.YagaConfirmationTokenService;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccount;
import ee.nikolas.resalepilot.workflow.yaga.account.YagaAccountService;
import ee.nikolas.resalepilot.workflow.yaga.publishing.automation.YagaBrowserAutomation;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaConditionMapper;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaFormFillResult;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedBrowserSession;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPreparedImageFile;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.publishing.model.YagaPublishResult;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublishedUrl;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.model.YagaPublishedUrlResolver;

import ee.nikolas.resalepilot.workflow.yaga.publishing.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationReconcileRequest;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.publishing.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class YagaPublicationSessionManager {

    private static final Logger log = LoggerFactory.getLogger(
            YagaPublicationSessionManager.class
    );

    private final YagaPublishingService publishingService;
    private final YagaBrowserAutomation browserAutomation;
    private final YagaConfirmationTokenService tokenService;
    private final YagaPublishingProperties properties;
    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final YagaPageDataClient pageDataClient;
    private final YagaAccountService accountService;
    private final TransactionTemplate transactionTemplate;
    private final YagaPublishedUrlResolver publishedUrlResolver =
            new YagaPublishedUrlResolver();
    private final ScheduledExecutorService expiryExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<UUID, Session> sessions =
            new ConcurrentHashMap<>();
    private final AtomicReference<Session> activeSession =
            new AtomicReference<>();

    @Autowired
    public YagaPublicationSessionManager(
            YagaPublishingService publishingService,
            YagaBrowserAutomation browserAutomation,
            YagaConfirmationTokenService tokenService,
            YagaPublishingProperties properties,
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            YagaPageDataClient pageDataClient,
            YagaAccountService accountService,
            PlatformTransactionManager transactionManager
    ) {
        this.publishingService = publishingService;
        this.browserAutomation = browserAutomation;
        this.tokenService = tokenService;
        this.properties = properties;
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.pageDataClient = pageDataClient;
        this.accountService = accountService;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    public YagaPublicationSessionManager(
            YagaPublishingService publishingService,
            YagaBrowserAutomation browserAutomation,
            YagaConfirmationTokenService tokenService,
            YagaPublishingProperties properties,
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            YagaPageDataClient pageDataClient,
            PlatformTransactionManager transactionManager
    ) {
        this(
                publishingService,
                browserAutomation,
                tokenService,
                properties,
                listingRepository,
                productImageRepository,
                pageDataClient,
                null,
                transactionManager
        );
    }

    public YagaPublicationPreparationResponse prepare(Long listingId) {
        return prepare(listingId, null);
    }

    public YagaPublicationPreparationResponse prepare(
            Long listingId,
            YagaAccount expectedAccount
    ) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getConfirmationTtl());
        String token = tokenService.generateToken();

        Session session = new Session(
                id,
                listingId,
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
                List<DownloadedDriveFile> downloadedFiles =
                        List.of();

                try {
                    YagaListingDraftData draft =
                            publishingService.loadDraft(listingId);
                    YagaAccount account = expectedAccount == null
                            ? resolveAccount(draft)
                            : expectedAccount;
                    validateAccount(draft, account);
                    downloadedFiles =
                            publishingService.downloadImages(draft);
                    List<YagaPreparedImageFile> files =
                            publishingService.toPreparedImageFiles(
                                    draft,
                                    downloadedFiles
                            );
                    YagaPreparedBrowserSession browserSession =
                            accountService == null
                                    ? browserAutomation.prepareSession(
                                            draft,
                                            files
                                    )
                                    : browserAutomation.prepareSession(
                                            draft,
                                            files,
                                            account
                                    );

                    publishingService.closeDownloadedFiles(
                            downloadedFiles
                    );
                    downloadedFiles = List.of();

                    YagaFormFillResult result =
                            browserSession.preparedForm();
                    publishingService.validateFormResult(
                            draft,
                            result
                    );

                    session.draft = draft;
                    session.browserSession = browserSession;
                    session.screenshotPath =
                            result.screenshotPath().toString();
                    session.status =
                            YagaPublicationStatus.AWAITING_CONFIRMATION;

                    return new YagaPublicationPreparationResponse(
                            id,
                            draft.listingId(),
                            draft.productId(),
                            result.imageCount(),
                            result.categoryPath(),
                            draft.condition(),
                            result.price(),
                            session.screenshotPath,
                            token,
                            expiresAt,
                            session.status
                    );

                } catch (RuntimeException exception) {
                    failAndClose(session, exception);
                    throw exception;

                } finally {
                    publishingService.closeDownloadedFiles(
                            downloadedFiles
                    );
                }
            });

        } catch (RuntimeException exception) {
            if (session.isTerminal()) {
                activeSession.compareAndSet(session, null);
            }
            throw exception;
        }
    }

    public YagaPublicationPreparationStatusResponse status(UUID id) {
        Session session = requireSession(id);
        return session.statusResponse();
    }

    public YagaPublishReadinessResponse publishReadiness(UUID id) {
        Session session = requireSession(id);

        return submit(session, () -> {
            if (Instant.now().isAfter(session.expiresAt) &&
                    !session.isTerminal()) {
                session.status = YagaPublicationStatus.EXPIRED;
                session.tokenHash = null;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
            }

            if (session.status !=
                    YagaPublicationStatus.AWAITING_CONFIRMATION ||
                    session.browserSession == null) {
                return new YagaPublishReadinessResponse(
                        session.id,
                        session.status,
                        session.newProductUrl,
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

            YagaPublishControlInspection inspection =
                    browserAutomation.inspectPublishControl(
                            session.browserSession
                    );

            return new YagaPublishReadinessResponse(
                    session.id,
                    session.status,
                    inspection.currentUrl(),
                    inspection.formStillValid(),
                    inspection.candidateCount(),
                    inspection.visibleCandidateCount(),
                    inspection.enabledCandidateCount(),
                    inspection.buttonText(),
                    inspection.tagName(),
                    inspection.typeAttribute(),
                    inspection.readyForConfirmation(),
                    inspection.inspectedAt()
            );
        });
    }

    public YagaPublicationConfirmResponse confirm(
            UUID id,
            YagaPublicationConfirmRequest request
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
                YagaFormFillResult verified =
                        browserAutomation.verifyPreparedForm(
                                session.browserSession
                        );
                publishingService.validateFormResult(
                        session.draft,
                        verified
                );

                YagaPublishControlInspection readiness =
                        browserAutomation.inspectPublishControl(
                                session.browserSession
                        );
                if (!readiness.readyForConfirmation()) {
                    throw new YagaPublishingFormException(
                            "Yaga publish button is not ready for confirmation"
                    );
                }

            } catch (RuntimeException exception) {
                session.lastSafeErrorMessage = exception.getMessage();
                throw exception;
            }

            session.status = YagaPublicationStatus.PUBLISHING;
            session.tokenHash = null;

            YagaPublishResult result =
                    browserAutomation.publishPreparedSession(
                            session.browserSession
                    );
            log.info(
                    "Yaga publication submit result: preparationId={} oldListingId={} clickAttempted={} currentUrlAfterSubmit={} detectedShopSlug={} detectedProductSlug={} publicProductUrlDetected={}",
                    session.id,
                    session.listingId,
                    result.clickPerformed(),
                    safeUrl(result.productUrl()),
                    result.shopSlug(),
                    result.productSlug(),
                    isPublicProductUrl(
                            result.productUrl(),
                            session.draft.shopSlug()
                    )
            );
            session.newProductUrl = result.productUrl();
            session.newShopSlug = result.shopSlug();
            session.newProductSlug = result.productSlug();
            session.publishedAt = result.publishedAt();

            if (result.clickPerformed() &&
                    (result.evidence() == null || result.evidence().confirmationAllowed())) {
                reconcilePublishedResult(session, result.productUrl(),
                        result.evidence() == null ? null : result.evidence().externalListingId(),
                        result.evidence() != null && "PREPARED_DRAFT_REQUIRES_DETAIL_VALIDATION".equals(result.evidence().reason()));
            } else {
                session.status =
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN;
                if (result.evidence() != null) {
                    var evidence = result.evidence();
                    session.lastSafeErrorMessage = "Publication evidence: " + evidence.outcome() +
                            "; reason=" + evidence.reason() + "; httpStatus=" + evidence.httpStatus() +
                            "; caseId=" + evidence.caseId();
                    log.warn("Yaga publication confirmation withheld: preparationId={} oldListingId={} " +
                                    "outcome={} reason={} httpStatus={} caseId={} localNewListingId=null",
                            session.id, session.listingId, evidence.outcome(), evidence.reason(),
                            evidence.httpStatus(), evidence.caseId());
                }
            }

            closeBrowser(session);
            activeSession.compareAndSet(session, null);

            return confirmResponse(session);
        });
    }

    public YagaPublicationConfirmResponse reconcile(
            UUID id,
            YagaPublicationReconcileRequest request
    ) {
        Session session = requireSession(id);

        return submit(session, () -> {
            if (session.status == YagaPublicationStatus.PUBLISHED ||
                    session.status ==
                            YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED) {
                return confirmResponse(session);
            }

            if (session.status !=
                    YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN) {
                throw new YagaPublicationInvalidStateException(
                        "Yaga publication preparation cannot be reconciled from status: " +
                                session.status
                );
            }

            String requestedUrl = request == null
                    ? null
                    : request.publicUrl();
            String sourceUrl = isBlank(requestedUrl)
                    ? session.newProductUrl
                    : requestedUrl;

            reconcilePublishedResult(session, sourceUrl);
            return confirmResponse(session);
        });
    }

    public YagaPublicationPreparationStatusResponse cancel(UUID id) {
        Session session = requireSession(id);
        return submit(session, () -> {
            if (!session.isTerminal()) {
                session.status = YagaPublicationStatus.CANCELLED;
                session.tokenHash = null;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
            }

            return session.statusResponse();
        });
    }

    private void reconcilePublishedResult(
            Session session,
            String sourceUrl
    ) {
        reconcilePublishedResult(session, sourceUrl, null);
    }

    private void reconcilePublishedResult(Session session, String sourceUrl, Long responseListingId) {
        reconcilePublishedResult(session, sourceUrl, responseListingId, false);
    }

    private void reconcilePublishedResult(Session session, String sourceUrl, Long responseListingId, boolean draftFallback) {
        YagaPublishedUrl resolved;
        YagaImportedProductData data;
        boolean detailFetched = false;

        try {
            if (draftFallback) {
                log.info("Yaga publication fallback: preparationId={} oldListingId={} fallbackStarted=true preparedDraftId={} " +
                                "preparedDraftSlug={} fallbackDetailFetchResult=PENDING fallbackValidationResult=PENDING",
                        session.id, session.listingId, responseListingId, session.newProductSlug);
            }
            log.info(
                    "Yaga publication result confirmation started: preparationId={} oldListingId={} sourceUrl={} expectedShopSlug={} remoteDetailFetchAttempted=false localNewListingId=null",
                    session.id,
                    session.listingId,
                    safeUrl(sourceUrl),
                    session.draft.shopSlug()
            );
            resolved = publishedUrlResolver.resolve(
                    sourceUrl,
                    session.draft.shopSlug()
            );
            log.info(
                    "Yaga publication URL resolved: preparationId={} oldListingId={} sourceUrl={} publicUrl={} shopSlug={} productSlug={} publicProductUrl={}",
                    session.id,
                    session.listingId,
                    safeUrl(sourceUrl),
                    safeUrl(resolved.publicUrl()),
                    resolved.shopSlug(),
                    resolved.productSlug(),
                    resolved.publicProductUrl()
            );
            data = pollPublishedProductData(resolved.publicUrl());
            detailFetched = true;
            if (draftFallback) {
                log.info("Yaga publication fallback: preparationId={} fallbackDetailFetchResult=SUCCESS fallbackValidationResult=PENDING",
                        session.id);
            }
            logPublishedData(session, data);
            if (responseListingId != null && !responseListingId.equals(data.externalId())) {
                throw new YagaPublishingFormException("Publication API identity differs from fetched listing identity");
            }
            validatePublishedData(session.draft, data);
            if (draftFallback && (!Objects.equals(session.draft.shopSlug(), data.shopSlug()) ||
                    !Objects.equals(resolved.productSlug(), data.productSlug()) ||
                    !"published".equals(data.status()) || data.hiddenAt() != null || data.deletedAt() != null ||
                    data.images() == null || data.images().size() != session.draft.images().size())) {
                throw new YagaPublishingFormException("Prepared draft remote identity, visibility or image count differs");
            }
            if (draftFallback) {
                log.info("Yaga publication fallback: preparationId={} fallbackDetailFetchResult=SUCCESS " +
                        "fallbackValidationResult=SUCCESS outcome=CONFIRMED_SUCCESS", session.id);
            }

        } catch (RuntimeException exception) {
            if (draftFallback) {
                log.warn("Yaga publication fallback: preparationId={} fallbackDetailFetchResult={} " +
                                "fallbackValidationResult={} outcome=RESULT_UNKNOWN exceptionClass={}",
                        session.id, detailFetched ? "SUCCESS" : "FAILED", detailFetched ? "FAILED" : "NOT_STARTED",
                        exception.getClass().getName());
            }
            session.status =
                    YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN;
            session.lastSafeErrorMessage =
                    "Published Yaga listing could not be confirmed";
            log.warn(
                    "Yaga publication result unsafe: preparationId={} oldListingId={} sourceUrl={} clickAttempted=true remoteDetailFetchAttempted={} localNewListingId=null exceptionClass={} rootCauseClass={} safeReason={}",
                    session.id,
                    session.listingId,
                    safeUrl(sourceUrl),
                    isResolvable(sourceUrl),
                    exception.getClass().getName(),
                    rootCause(exception).getClass().getName(),
                    exception.getMessage()
            );
            return;
        }

        session.newProductUrl = resolved.publicUrl();
        session.newShopSlug = resolved.shopSlug();
        session.newProductSlug = resolved.productSlug();

        try {
            syncPublishedListing(session, resolved, data);
            if (session.publishedAt == null) {
                session.publishedAt = Instant.now();
            }
            session.status = YagaPublicationStatus.PUBLISHED;

        } catch (RuntimeException exception) {
            session.status =
                    YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED;
            session.lastSafeErrorMessage =
                    "Published Yaga listing but DB sync failed";
        }
    }

    private void validateAccount(
            YagaListingDraftData draft,
            YagaAccount account
    ) {
        if (account == null ||
                !account.getId().equals(draft.yagaAccountId()) ||
                !account.getShopSlug().equals(draft.shopSlug())) {
            throw new YagaPublicationInvalidStateException(
                    "Yaga account does not match publication listing"
            );
        }
    }

    private YagaAccount resolveAccount(YagaListingDraftData draft) {
        if (accountService != null) {
            return accountService.getEntity(draft.yagaAccountId());
        }
        YagaAccount account = new YagaAccount(
                "Yaga account",
                draft.shopSlug(),
                null,
                10
        );
        account.setId(draft.yagaAccountId());
        return account;
    }

    private YagaImportedProductData pollPublishedProductData(
            String publicUrl
    ) {
        long started = System.nanoTime();
        long timeoutNanos = properties.getPublishDataPollTimeout().toNanos();
        RuntimeException lastException = null;
        int attempt = 0;

        while (System.nanoTime() - started < timeoutNanos) {
            attempt++;
            YagaImportedProductData data = null;
            try {
                log.info(
                        "Yaga publication remote detail fetch started: publicUrl={}",
                        safeUrl(publicUrl)
                );
                data = pageDataClient.getProduct(publicUrl);
            } catch (RuntimeException exception) {
                lastException = exception;
            }
            String remoteStatus = data == null ? "UNAVAILABLE" :
                    java.util.Set.of("published", "pending", "hidden", "deleted", "draft", "sold", "error")
                            .contains(Objects.toString(data.status(), "")) ? data.status() : "UNRECOGNIZED";
            long elapsed = System.nanoTime() - started;
            String outcome = elapsed >= timeoutNanos ? "TIMEOUT" : data == null ? "RETRY_FETCH" :
                    data.hiddenAt() != null || data.deletedAt() != null ? "REMOTE_STATE_REJECTED" :
                    "published".equals(data.status()) ? "PUBLISHED_REQUIRES_VALIDATION" :
                    "pending".equals(data.status()) ? "WAITING_PENDING" : "REMOTE_STATE_REJECTED";
            log.info("Yaga publication detail polling: publicUrl={} attempt={} elapsedMs={} remoteStatus={} pollingOutcome={}",
                    safeUrl(publicUrl), attempt, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed), remoteStatus, outcome);
            if ("TIMEOUT".equals(outcome)) break;
            if ("PUBLISHED_REQUIRES_VALIDATION".equals(outcome)) return data;
            if ("REMOTE_STATE_REJECTED".equals(outcome)) {
                throw new YagaPublishingFormException("Published Yaga page has an inactive or unrecognized state");
            }
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining > 0) sleepPollInterval(Math.min(remaining, Math.max(1_000_000L,
                    properties.getPublishDataPollInterval().toNanos())));
        }

        log.warn("Yaga publication detail polling: publicUrl={} attempt={} elapsedMs={} pollingOutcome=TIMEOUT",
                safeUrl(publicUrl), attempt, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));

        if (lastException != null) {
            log.warn(
                    "Yaga publication remote detail fetch failed: publicUrl={} exceptionClass={} rootCauseClass={} safeReason={}",
                    safeUrl(publicUrl),
                    lastException.getClass().getName(),
                    rootCause(lastException).getClass().getName(),
                    lastException.getMessage()
            );
        }
        throw new YagaPublishingFormException(
                "Published Yaga page data was not available in time",
                lastException
        );
    }

    private void sleepPollInterval(long nanos) {
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new YagaPublishingFormException(
                    "Yaga published page polling was interrupted",
                    exception
            );
        }
    }

    private void syncPublishedListing(
            Session session,
            YagaPublishedUrl resolved,
            YagaImportedProductData data
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            MarketplaceListing oldListing =
                    listingRepository.findByIdWithImagesAndProductImages(
                                    session.listingId
                            )
                            .orElseThrow(() ->
                                    new MarketplaceListingNotFoundException(
                                            session.listingId
                                    )
                            );
            Optional<MarketplaceListing> existingListing =
                    listingRepository
                            .findByYagaAccountIdAndMarketplaceAndExternalListingId(
                                    oldListing.getYagaAccount().getId(),
                                    Marketplace.YAGA,
                                    data.externalId().toString()
                            )
                            .or(() ->
                                    listingRepository
                                            .findByYagaAccountIdAndMarketplaceAndShopSlugAndProductSlug(
                                                    oldListing.getYagaAccount().getId(),
                                                    Marketplace.YAGA,
                                                    resolved.shopSlug(),
                                                    resolved.productSlug()
                                            )
                            );
            if (existingListing.isPresent()) {
                return;
            }

            Product product = oldListing.getProduct();

            MarketplaceListing listing =
                    new MarketplaceListing(
                            product,
                            Marketplace.YAGA,
                            data.externalId().toString(),
                            resolved.publicUrl()
                    );
            listing.setShopSlug(resolved.shopSlug());
            listing.setYagaAccount(oldListing.getYagaAccount());
            listing.setProductSlug(resolved.productSlug());
            listing.setStatus(MarketplaceListingStatus.PUBLISHED);
            listing.setExternalStatus(data.status());
            if (data.condition() != null) {
                listing.setExternalConditionId(data.condition().id());
                listing.setExternalConditionName(data.condition().name());
            }
            listing.setCurrent(false);
            listing.setExternalCreatedAt(data.createdAt());
            listing.setExternalUpdatedAt(data.updatedAt());
            listing.setLastSyncedAt(Instant.now());

            for (int index = 0;
                 index < data.categoryPath().size();
                 index++) {
                YagaImportedProductData.Category category =
                        data.categoryPath().get(index);
                listing.addCategory(
                        new MarketplaceListingCategory(
                                index,
                                category.id(),
                                category.parentId(),
                                category.title()
                        )
                );
            }

            List<ProductImage> productImages =
                    productImageRepository
                            .findAllByProductIdOrderByDisplayOrderAsc(
                                    product.getId()
                            );

            for (int index = 0;
                 index < data.images().size();
                 index++) {
                YagaImportedProductData.Image image =
                        data.images().get(index);
                MarketplaceListingImage listingImage =
                        new MarketplaceListingImage(
                                image.id(),
                                image.originalUrl(),
                                image.fileName(),
                                index
                        );
                if (index < productImages.size()) {
                    listingImage.setProductImage(
                            productImages.get(index)
                    );
                }
                listing.addImage(listingImage);
            }

            listingRepository.saveAndFlush(listing);
        });
    }

    private void validatePublishedData(
            YagaListingDraftData draft,
            YagaImportedProductData data
    ) {
        String expectedDescription = trimToNull(draft.description());
        String actualDescription = trimToNull(data.description());
        boolean descriptionMatch = Objects.equals(
                actualDescription,
                expectedDescription
        );

        boolean priceMatch = data.price() != null &&
                data.price().compareTo(draft.askingPrice()) == 0;

        List<String> categoryPath = data.categoryPath()
                .stream()
                .map(YagaImportedProductData.Category::title)
                .toList();
        boolean categoryMatch = categoryPath.equals(draft.categoryPath());

        String expectedCondition =
                YagaConditionMapper.toYaga(draft.condition()).label();
        String actualCondition = data.condition() == null
                ? null
                : data.condition().name();
        boolean conditionMatch = Objects.equals(
                expectedCondition,
                actualCondition
        );

        log.info(
                "Yaga publication validation diagnostic: expectedDescriptionHash={} actualDescriptionHash={} descriptionMatch={} expectedPrice={} actualPrice={} priceMatch={} expectedCategoryPath={} actualCategoryPath={} categoryMatch={} expectedCondition={} actualCondition={} conditionMatch={}",
                safeHash(expectedDescription),
                safeHash(actualDescription),
                descriptionMatch,
                draft.askingPrice(),
                data.price(),
                priceMatch,
                draft.categoryPath(),
                categoryPath,
                categoryMatch,
                expectedCondition,
                actualCondition,
                conditionMatch
        );

        if (!descriptionMatch) {
            throw new YagaPublishingFormException(
                    "Published Yaga description does not match draft"
            );
        }

        if (!priceMatch) {
            throw new YagaPublishingFormException(
                    "Published Yaga price does not match draft"
            );
        }

        if (!categoryMatch) {
            throw new YagaPublishingFormException(
                    "Published Yaga category path does not match draft"
            );
        }

        if (!conditionMatch) {
            throw new YagaPublishingFormException(
                    "Published Yaga condition does not match draft"
            );
        }
    }

    private void logPublishedData(
            Session session,
            YagaImportedProductData data
    ) {
        log.info(
                "Yaga publication remote detail fetch completed: preparationId={} oldListingId={} externalListingId={} productSlug={} status={} price={} conditionId={} conditionName={} categoryIdentity={} imageCount={}",
                session.id,
                session.listingId,
                data.externalId(),
                data.productSlug(),
                data.status(),
                data.price(),
                data.condition() == null ? null : data.condition().id(),
                data.condition() == null ? null : data.condition().name(),
                data.categoryPath()
                        .stream()
                        .map(category -> category.id() + ":" +
                                category.title())
                        .toList(),
                data.images().size()
        );
    }

    private boolean isResolvable(String sourceUrl) {
        return publishedUrlResolver.isResolvable(
                sourceUrl,
                "placeholder"
        );
    }

    private boolean isPublicProductUrl(
            String sourceUrl,
            String fallbackShopSlug
    ) {
        try {
            return publishedUrlResolver
                    .resolve(sourceUrl, fallbackShopSlug)
                    .publicProductUrl();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeHash(String value) {
        return value == null
                ? null
                : Integer.toHexString(value.hashCode());
    }

    private String safeUrl(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null) {
                return path == null ? value : path;
            }
            return uri.getScheme() + "://" + host +
                    (path == null ? "" : path);
        } catch (java.net.URISyntaxException exception) {
            return "invalid-url";
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void ensureConfirmation(
            YagaPublicationConfirmRequest request,
            Session session
    ) {
        if (!"PUBLISH".equals(request.confirmationPhrase()) ||
                session.tokenHash == null ||
                !tokenService.matches(
                        request.confirmationToken(),
                        session.tokenHash
                )) {
            throw new YagaPublicationForbiddenException();
        }
    }

    private YagaPublicationConfirmResponse confirmResponse(
            Session session
    ) {
        boolean yagaPublished =
                session.status == YagaPublicationStatus.PUBLISHED ||
                        session.status ==
                                YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED;

        return new YagaPublicationConfirmResponse(
                session.id,
                session.listingId,
                yagaPublished,
                session.newProductUrl,
                session.newShopSlug,
                session.newProductSlug,
                session.publishedAt,
                session.status
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void ensureAwaiting(Session session) {
        if (session.status == YagaPublicationStatus.EXPIRED) {
            throw new YagaPublicationExpiredException();
        }

        if (session.status !=
                YagaPublicationStatus.AWAITING_CONFIRMATION) {
            throw new YagaPublicationInvalidStateException(
                    "Yaga publication preparation cannot be confirmed from status: " +
                            session.status
            );
        }
    }

    private void ensureNotExpired(Session session) {
        if (Instant.now().isAfter(session.expiresAt)) {
            session.status = YagaPublicationStatus.EXPIRED;
            session.tokenHash = null;
            closeBrowser(session);
            throw new YagaPublicationExpiredException();
        }
    }

    private void expire(UUID id) {
        Session session = activeSession.get();
        if (session == null || !session.id.equals(id)) {
            return;
        }

        submit(session, () -> {
            if (!session.isTerminal() &&
                    session.status != YagaPublicationStatus.PUBLISHING) {
                session.status = YagaPublicationStatus.EXPIRED;
                session.tokenHash = null;
                closeBrowser(session);
                activeSession.compareAndSet(session, null);
            }
            return null;
        });
    }

    private void failAndClose(Session session, RuntimeException exception) {
        session.status = YagaPublicationStatus.FAILED;
        session.tokenHash = null;
        session.lastSafeErrorMessage = exception.getMessage();
        closeBrowser(session);
        activeSession.compareAndSet(session, null);
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

    private <T> T submit(
            Session session,
            Callable<T> callable
    ) {
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
        private final Long listingId;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final ExecutorService executor =
                Executors.newSingleThreadExecutor();
        private volatile YagaPublicationStatus status =
                YagaPublicationStatus.PREPARING;
        private volatile byte[] tokenHash;
        private volatile YagaListingDraftData draft;
        private volatile YagaPreparedBrowserSession browserSession;
        private volatile String screenshotPath;
        private volatile String newProductUrl;
        private volatile String newShopSlug;
        private volatile String newProductSlug;
        private volatile Instant publishedAt;
        private volatile String lastSafeErrorMessage;
        private volatile ScheduledFuture<?> expiryFuture;

        private Session(
                UUID id,
                Long listingId,
                Instant createdAt,
                Instant expiresAt,
                byte[] tokenHash
        ) {
            this.id = id;
            this.listingId = listingId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.tokenHash = tokenHash;
        }

        private boolean isTerminal() {
            return status == YagaPublicationStatus.PUBLISHED ||
                    status == YagaPublicationStatus.PUBLISHED_DB_SYNC_FAILED ||
                    status == YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN ||
                    status == YagaPublicationStatus.FAILED ||
                    status == YagaPublicationStatus.CANCELLED ||
                    status == YagaPublicationStatus.EXPIRED;
        }

        private YagaPublicationPreparationStatusResponse statusResponse() {
            return new YagaPublicationPreparationStatusResponse(
                    id,
                    listingId,
                    status,
                    createdAt,
                    expiresAt,
                    screenshotPath,
                    newProductUrl,
                    lastSafeErrorMessage
            );
        }
    }
}
