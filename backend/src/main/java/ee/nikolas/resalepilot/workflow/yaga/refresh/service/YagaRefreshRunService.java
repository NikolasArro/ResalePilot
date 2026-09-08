package ee.nikolas.resalepilot.workflow.yaga.refresh.service;

import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.repository.ProductRepository;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.config.YagaRefreshProperties;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshJobResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunRequest;
import ee.nikolas.resalepilot.workflow.yaga.refresh.dto.YagaRefreshRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJob;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRun;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunMode;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.refresh.entity.YagaRefreshTriggerType;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRunNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.refresh.repository.YagaRefreshRunRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class YagaRefreshRunService {

    private final YagaRefreshProperties properties;
    private final YagaRefreshRunRepository runRepository;
    private final MarketplaceListingRepository listingRepository;
    private final ProductRepository productRepository;
    private final YagaRefreshCandidateSelector candidateSelector;
    private final Clock clock;

    public YagaRefreshRunService(
            YagaRefreshProperties properties,
            YagaRefreshRunRepository runRepository,
            MarketplaceListingRepository listingRepository,
            ProductRepository productRepository,
            YagaRefreshCandidateSelector candidateSelector,
            Clock clock
    ) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.listingRepository = listingRepository;
        this.productRepository = productRepository;
        this.candidateSelector = candidateSelector;
        this.clock = clock;
    }

    @Transactional
    public YagaRefreshRunResponse startManualDryRun(
            YagaRefreshRunRequest request
    ) {
        if (request.mode() != null
                && request.mode() != YagaRefreshMode.DRY_RUN
                && request.mode() != YagaRefreshMode.MANUAL) {
            throw new YagaRefreshRequestInvalidException(
                    "Only MANUAL refresh planning is supported"
            );
        }
        if (request.idempotencyKey() == null ||
                request.idempotencyKey().isBlank()) {
            throw new YagaRefreshRequestInvalidException(
                    "idempotencyKey is required"
            );
        }

        return runRepository
                .findWithJobsByTriggerTypeAndIdempotencyKey(
                        YagaRefreshTriggerType.MANUAL,
                        request.idempotencyKey()
                )
                .map(this::toResponse)
                .orElseGet(() -> createManualPlan(
                        YagaRefreshTriggerType.MANUAL,
                        request.batchSize(),
                        request.idempotencyKey()
                ));
    }

    @Transactional
    public YagaRefreshRunResponse startScheduledDryRun(
            String idempotencyKey
    ) {
        return runRepository
                .findWithJobsByTriggerTypeAndIdempotencyKey(
                        YagaRefreshTriggerType.SCHEDULED,
                        idempotencyKey
                )
                .map(this::toResponse)
                .orElseGet(() -> createDryRun(
                        YagaRefreshTriggerType.SCHEDULED,
                        null,
                        idempotencyKey
                ));
    }

    @Transactional(readOnly = true)
    public YagaRefreshRunResponse getRun(UUID runId) {
        return runRepository.findWithJobsById(runId)
                .map(this::toResponse)
                .orElseThrow(() ->
                        new YagaRefreshRunNotFoundException(runId));
    }

    @Transactional
    public YagaRefreshRunResponse cancelRun(UUID runId) {
        YagaRefreshRun run = runRepository.findWithJobsById(runId)
                .orElseThrow(() ->
                        new YagaRefreshRunNotFoundException(runId));

        if (run.getStatus() != YagaRefreshRunStatus.AWAITING_CONFIRMATION) {
            throw new YagaRefreshInvalidStateException(
                    "Yaga refresh run can only be cancelled while awaiting confirmation"
            );
        }

        run.setStatus(YagaRefreshRunStatus.CANCELLED);
        run.setCompletedAt(clock.instant());
        return toResponse(runRepository.saveAndFlush(run));
    }

    private YagaRefreshRunResponse createManualPlan(
            YagaRefreshTriggerType triggerType,
            Integer requestedBatchSize,
            String idempotencyKey
    ) {
        int batchSize = effectiveBatchSize(requestedBatchSize);
        Instant now = clock.instant();
        YagaRefreshRun run = new YagaRefreshRun(
                triggerType,
                YagaRefreshRunMode.MANUAL,
                batchSize,
                idempotencyKey,
                now
        );
        run.setStatus(YagaRefreshRunStatus.PREPARING);
        run = runRepository.saveAndFlush(run);

        List<YagaRefreshCandidate> candidates =
                candidateSelector.selectForUpdate(batchSize);

        int selectionOrder = 0;
        try {
            for (YagaRefreshCandidate candidate : candidates) {
                Product product = productRepository
                        .getReferenceById(candidate.productId());
                MarketplaceListing listing = listingRepository
                        .getReferenceById(candidate.listingId());

                YagaRefreshJob job = new YagaRefreshJob(
                        product,
                        listing,
                        candidate.externalListingId(),
                        candidate.shopSlug(),
                        candidate.productSlug(),
                        candidate.externalUrl(),
                        candidate.title(),
                        candidate.externalCreatedAt(),
                        candidate.listingCreatedAt(),
                        candidate.productImageCount(),
                        candidate.listingImageCount(),
                        selectionOrder++,
                        now
                );
                run.addJob(job);
            }

            run.setSelectedJobCount(candidates.size());
            run.setStatus(YagaRefreshRunStatus.AWAITING_CONFIRMATION);
            return toResponse(runRepository.saveAndFlush(run));

        } catch (DataIntegrityViolationException exception) {
            run.setStatus(YagaRefreshRunStatus.FAILED);
            run.setLastErrorCode("ACTIVE_REFRESH_JOB_CONFLICT");
            run.setLastSafeErrorMessage(
                    "A selected product already has an active refresh job"
            );
            run.setCompletedAt(clock.instant());
            runRepository.saveAndFlush(run);
            throw exception;
        }
    }

    private YagaRefreshRunResponse createDryRun(
            YagaRefreshTriggerType triggerType,
            Integer requestedBatchSize,
            String idempotencyKey
    ) {
        int batchSize = effectiveBatchSize(requestedBatchSize);
        Instant now = clock.instant();
        YagaRefreshRun run = new YagaRefreshRun(
                triggerType,
                YagaRefreshRunMode.DRY_RUN,
                batchSize,
                idempotencyKey,
                now
        );
        run.setStatus(YagaRefreshRunStatus.SELECTING);
        run = runRepository.saveAndFlush(run);

        List<YagaRefreshCandidate> candidates =
                candidateSelector.selectForUpdate(batchSize);

        int selectionOrder = 0;
        try {
            for (YagaRefreshCandidate candidate : candidates) {
                Product product = productRepository
                        .getReferenceById(candidate.productId());
                MarketplaceListing listing = listingRepository
                        .getReferenceById(candidate.listingId());

                YagaRefreshJob job = new YagaRefreshJob(
                        product,
                        listing,
                        candidate.externalListingId(),
                        candidate.shopSlug(),
                        candidate.productSlug(),
                        candidate.externalUrl(),
                        candidate.title(),
                        candidate.externalCreatedAt(),
                        candidate.listingCreatedAt(),
                        candidate.productImageCount(),
                        candidate.listingImageCount(),
                        selectionOrder++,
                        now
                );
                run.addJob(job);
            }

            run.setSelectedJobCount(candidates.size());
            run.setStatus(YagaRefreshRunStatus.DRY_RUN_COMPLETED);
            run.setCompletedAt(now);
            return toResponse(runRepository.saveAndFlush(run));

        } catch (DataIntegrityViolationException exception) {
            run.setStatus(YagaRefreshRunStatus.FAILED);
            run.setLastErrorCode("ACTIVE_REFRESH_JOB_CONFLICT");
            run.setLastSafeErrorMessage(
                    "A selected product already has an active refresh job"
            );
            run.setCompletedAt(clock.instant());
            runRepository.saveAndFlush(run);
            throw exception;
        }
    }

    private int effectiveBatchSize(Integer requestedBatchSize) {
        int batchSize = requestedBatchSize == null
                ? properties.batchSize()
                : requestedBatchSize;

        if (batchSize < 1) {
            throw new YagaRefreshRequestInvalidException(
                    "batchSize must be at least 1"
            );
        }
        if (batchSize > properties.maxBatchSize()) {
            throw new YagaRefreshRequestInvalidException(
                    "batchSize must not exceed maxBatchSize"
            );
        }
        return batchSize;
    }

    private YagaRefreshRunResponse toResponse(YagaRefreshRun run) {
        List<YagaRefreshJobResponse> candidates = run.getJobs()
                .stream()
                .sorted(Comparator.comparingInt(
                        YagaRefreshJob::getSelectionOrder
                ))
                .map(job -> new YagaRefreshJobResponse(
                        job.getId(),
                        job.getSelectionOrder(),
                        job.getProduct().getId(),
                        job.getProduct().getSku(),
                        snapshotProductTitle(job),
                        job.getOldListing().getId(),
                        snapshotOldExternalListingId(job),
                        snapshotOldShopSlug(job),
                        snapshotOldProductSlug(job),
                        snapshotOldPublicUrl(job),
                        snapshotSelectedExternalCreatedAt(job),
                        snapshotSelectedListingCreatedAt(job),
                        snapshotOrderingTimestamp(job),
                        job.getExpectedProductImageCount(),
                        job.getExpectedListingImageCount(),
                        job.getStatus()
                ))
                .toList();

        return new YagaRefreshRunResponse(
                run.getId(),
                run.getTriggerType(),
                run.getMode(),
                run.getStatus(),
                run.getRequestedBatchSize(),
                run.getSelectedJobCount(),
                run.getCreatedAt(),
                run.getCompletedAt(),
                candidates
        );
    }

    private String snapshotProductTitle(YagaRefreshJob job) {
        return job.getProductTitle() == null
                ? job.getProduct().getTitle()
                : job.getProductTitle();
    }

    private String snapshotOldExternalListingId(YagaRefreshJob job) {
        return job.getOldExternalListingId() == null
                ? job.getOldListing().getExternalListingId()
                : job.getOldExternalListingId();
    }

    private String snapshotOldShopSlug(YagaRefreshJob job) {
        return job.getOldShopSlug() == null
                ? job.getOldListing().getShopSlug()
                : job.getOldShopSlug();
    }

    private String snapshotOldProductSlug(YagaRefreshJob job) {
        return job.getOldProductSlug() == null
                ? job.getOldListing().getProductSlug()
                : job.getOldProductSlug();
    }

    private String snapshotOldPublicUrl(YagaRefreshJob job) {
        return job.getOldPublicUrl() == null
                ? job.getOldListing().getExternalUrl()
                : job.getOldPublicUrl();
    }

    private Instant snapshotSelectedExternalCreatedAt(YagaRefreshJob job) {
        return job.getSelectedExternalCreatedAt() == null
                ? job.getOldListing().getExternalCreatedAt()
                : job.getSelectedExternalCreatedAt();
    }

    private Instant snapshotSelectedListingCreatedAt(YagaRefreshJob job) {
        return job.getSelectedListingCreatedAt() == null
                ? job.getOldListing().getCreatedAt()
                : job.getSelectedListingCreatedAt();
    }

    private Instant snapshotOrderingTimestamp(YagaRefreshJob job) {
        Instant externalCreatedAt =
                snapshotSelectedExternalCreatedAt(job);
        if (externalCreatedAt != null) {
            return externalCreatedAt;
        }

        return snapshotSelectedListingCreatedAt(job);
    }
}
