package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.config.YagaPublishingProperties;
import ee.nikolas.resalepilot.dto.YagaPublicationConfirmRequest;
import ee.nikolas.resalepilot.dto.YagaPublicationConfirmResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationPreparationResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationPreparationStatusResponse;
import ee.nikolas.resalepilot.dto.YagaPublicationReconcileRequest;
import ee.nikolas.resalepilot.dto.YagaPublicationStatus;
import ee.nikolas.resalepilot.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.dto.YagaPublishReadinessResponse;
import ee.nikolas.resalepilot.entity.*;
import ee.nikolas.resalepilot.exception.*;
import ee.nikolas.resalepilot.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.repository.ProductImageRepository;
import ee.nikolas.resalepilot.yaga.YagaImportedProductData;
import ee.nikolas.resalepilot.yaga.YagaPageDataClient;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final YagaPublishingService publishingService;
    private final YagaBrowserAutomation browserAutomation;
    private final YagaConfirmationTokenService tokenService;
    private final YagaPublishingProperties properties;
    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final YagaPageDataClient pageDataClient;
    private final TransactionTemplate transactionTemplate;
    private final YagaPublishedUrlResolver publishedUrlResolver =
            new YagaPublishedUrlResolver();
    private final ScheduledExecutorService expiryExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<UUID, Session> sessions =
            new ConcurrentHashMap<>();
    private final AtomicReference<Session> activeSession =
            new AtomicReference<>();

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
        this.publishingService = publishingService;
        this.browserAutomation = browserAutomation;
        this.tokenService = tokenService;
        this.properties = properties;
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.pageDataClient = pageDataClient;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    public YagaPublicationPreparationResponse prepare(Long listingId) {
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
                    downloadedFiles =
                            publishingService.downloadImages(draft);
                    List<YagaPreparedImageFile> files =
                            publishingService.toPreparedImageFiles(
                                    draft,
                                    downloadedFiles
                            );
                    YagaPreparedBrowserSession browserSession =
                            browserAutomation.prepareSession(
                                    draft,
                                    files
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
            session.newProductUrl = result.productUrl();
            session.newShopSlug = result.shopSlug();
            session.newProductSlug = result.productSlug();
            session.publishedAt = result.publishedAt();

            if (result.clickPerformed()) {
                reconcilePublishedResult(session, result.productUrl());
            } else {
                session.status =
                        YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN;
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
        YagaPublishedUrl resolved;
        YagaImportedProductData data;

        try {
            resolved = publishedUrlResolver.resolve(
                    sourceUrl,
                    session.draft.shopSlug()
            );
            data = pollPublishedProductData(resolved.publicUrl());
            validatePublishedData(session.draft, data);

        } catch (RuntimeException exception) {
            session.status =
                    YagaPublicationStatus.PUBLISH_RESULT_UNKNOWN;
            session.lastSafeErrorMessage =
                    "Published Yaga listing could not be confirmed";
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

    private YagaImportedProductData pollPublishedProductData(
            String publicUrl
    ) {
        Instant deadline = Instant.now()
                .plus(properties.getPublishDataPollTimeout());
        RuntimeException lastException = null;

        while (!Instant.now().isAfter(deadline)) {
            try {
                return pageDataClient.getProduct(publicUrl);
            } catch (RuntimeException exception) {
                lastException = exception;
                sleepPollInterval();
            }
        }

        throw new YagaPublishingFormException(
                "Published Yaga page data was not available in time",
                lastException
        );
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(
                    properties.getPublishDataPollInterval().toMillis()
            );
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
            Optional<MarketplaceListing> existingListing =
                    listingRepository.findByMarketplaceAndExternalListingId(
                            Marketplace.YAGA,
                            data.externalId().toString()
                    )
                            .or(() ->
                                    listingRepository
                                            .findByMarketplaceAndShopSlugAndProductSlug(
                                                    Marketplace.YAGA,
                                                    resolved.shopSlug(),
                                                    resolved.productSlug()
                                            )
                            );
            if (existingListing.isPresent()) {
                return;
            }

            MarketplaceListing oldListing =
                    listingRepository.findByIdWithImagesAndProductImages(
                                    session.listingId
                            )
                            .orElseThrow(() ->
                                    new MarketplaceListingNotFoundException(
                                            session.listingId
                                    )
                            );
            Product product = oldListing.getProduct();

            MarketplaceListing listing =
                    new MarketplaceListing(
                            product,
                            Marketplace.YAGA,
                            data.externalId().toString(),
                            resolved.publicUrl()
                    );
            listing.setShopSlug(resolved.shopSlug());
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
        if (data.description() == null ||
                !data.description().trim()
                        .equals(draft.description().trim())) {
            throw new YagaPublishingFormException(
                    "Published Yaga description does not match draft"
            );
        }

        if (data.price() == null ||
                data.price().compareTo(draft.askingPrice()) != 0) {
            throw new YagaPublishingFormException(
                    "Published Yaga price does not match draft"
            );
        }

        List<String> categoryPath = data.categoryPath()
                .stream()
                .map(YagaImportedProductData.Category::title)
                .toList();

        if (!categoryPath.equals(draft.categoryPath())) {
            throw new YagaPublishingFormException(
                    "Published Yaga category path does not match draft"
            );
        }

        ProductCondition publishedCondition =
                mapPublishedCondition(data.condition());
        if (publishedCondition != draft.condition()) {
            throw new YagaPublishingFormException(
                    "Published Yaga condition does not match draft"
            );
        }
    }

    private ProductCondition mapPublishedCondition(
            YagaImportedProductData.Condition condition
    ) {
        if (condition == null || condition.id() == null) {
            throw new YagaPublishingFormException(
                    "Published Yaga condition is missing"
            );
        }

        return switch (condition.id().intValue()) {
            case 1 -> ProductCondition.NEW_WITHOUT_TAGS;
            case 2 -> ProductCondition.VERY_GOOD;
            case 3 -> ProductCondition.GOOD;
            case 4 -> ProductCondition.SATISFACTORY;
            default -> throw new YagaPublishingFormException(
                    "Published Yaga condition is unsupported"
            );
        };
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
