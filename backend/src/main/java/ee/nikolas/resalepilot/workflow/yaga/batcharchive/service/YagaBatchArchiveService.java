package ee.nikolas.resalepilot.workflow.yaga.batcharchive.service;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.entity.ProductStatus;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveService;
import ee.nikolas.resalepilot.workflow.yaga.archive.YagaImageArchiveException;
import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.config.YagaBatchArchiveProperties;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchiveJobResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.dto.YagaBatchArchiveRunResponse;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJob;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveJobStatus;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRun;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.entity.YagaBatchArchiveRunStatus;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception.YagaBatchArchiveInvalidStateException;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception.YagaBatchArchiveRequestInvalidException;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.exception.YagaBatchArchiveRunNotFoundException;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository.YagaBatchArchiveJobRepository;
import ee.nikolas.resalepilot.workflow.yaga.batcharchive.repository.YagaBatchArchiveRunRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class YagaBatchArchiveService {

    private static final String CONFIRMATION_PHRASE = "ARCHIVE";

    private final YagaBatchArchiveProperties properties;
    private final YagaBatchArchiveRunRepository runRepository;
    private final YagaBatchArchiveJobRepository jobRepository;
    private final MarketplaceListingRepository listingRepository;
    private final YagaImageArchiveService archiveService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public YagaBatchArchiveService(
            YagaBatchArchiveProperties properties,
            YagaBatchArchiveRunRepository runRepository,
            YagaBatchArchiveJobRepository jobRepository,
            MarketplaceListingRepository listingRepository,
            YagaImageArchiveService archiveService,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.listingRepository = listingRepository;
        this.archiveService = archiveService;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public YagaBatchArchiveRunResponse prepare(
            Integer requestedMaxListings,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        int maxListings = resolveMaxListings(requestedMaxListings);

        if (idempotencyKey != null) {
            Optional<YagaBatchArchiveRun> existing =
                    transactionTemplate.execute(status ->
                            runRepository.findByIdempotencyKey(
                                    idempotencyKey
                            )
                    );
            if (existing != null && existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        YagaBatchArchiveRun saved;
        try {
            saved = transactionTemplate.execute(status -> {
                if (idempotencyKey != null) {
                    Optional<YagaBatchArchiveRun> concurrent =
                            runRepository.findByIdempotencyKey(
                                    idempotencyKey
                            );
                    if (concurrent.isPresent()) {
                        return concurrent.get();
                    }
                }

                Instant now = clock.instant();
                YagaBatchArchiveRun run = new YagaBatchArchiveRun(
                        maxListings,
                        idempotencyKey,
                        now
                );

                List<MarketplaceListing> candidates =
                        jobRepository.findEligibleListings(
                                Marketplace.YAGA,
                                MarketplaceListingStatus.PUBLISHED,
                                ProductStatus.ARCHIVED,
                                PageRequest.of(0, maxListings)
                        );

                int order = 0;
                for (MarketplaceListing candidate : candidates) {
                    MarketplaceListing listing =
                            listingRepository
                                    .findByIdWithImagesAndProductImages(
                                            candidate.getId()
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    candidate.getId()
                                            )
                                    );
                    run.addJob(new YagaBatchArchiveJob(
                            listing.getProduct(),
                            listing,
                            order++,
                            listing.getImages().size(),
                            linkedImageCount(listing),
                            now
                    ));
                }
                run.setSelectedJobCount(candidates.size());
                run.setStatus(
                        YagaBatchArchiveRunStatus.AWAITING_CONFIRMATION
                );
                return runRepository.saveAndFlush(run);
            });
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey != null) {
                Optional<YagaBatchArchiveRun> existing =
                        runRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return toResponse(existing.get());
                }
            }
            throw exception;
        }

        if (saved == null) {
            throw new IllegalStateException(
                    "Yaga batch archive preparation returned no run"
            );
        }
        return get(saved.getId());
    }

    public YagaBatchArchiveRunResponse get(UUID runId) {
        return runRepository.findWithJobsById(runId)
                .map(this::toResponse)
                .orElseThrow(() ->
                        new YagaBatchArchiveRunNotFoundException(runId)
                );
    }

    public YagaBatchArchiveRunResponse cancel(UUID runId) {
        YagaBatchArchiveRun run = transactionTemplate.execute(status -> {
            YagaBatchArchiveRun locked = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaBatchArchiveRunNotFoundException(runId)
                    );
            if (locked.getStatus() !=
                    YagaBatchArchiveRunStatus.AWAITING_CONFIRMATION) {
                throw new YagaBatchArchiveInvalidStateException(
                        "Yaga batch archive run cannot be cancelled after archiving started"
                );
            }
            locked.setStatus(YagaBatchArchiveRunStatus.CANCELLED);
            locked.setCompletedAt(clock.instant());
            return runRepository.saveAndFlush(locked);
        });
        if (run == null) {
            throw new IllegalStateException("Yaga batch archive run not loaded");
        }
        return get(run.getId());
    }

    public YagaBatchArchiveRunResponse confirm(
            UUID runId,
            String confirmationPhrase
    ) {
        if (!CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new YagaBatchArchiveRequestInvalidException(
                    "Confirmation phrase must be ARCHIVE"
            );
        }

        YagaBatchArchiveRun run = markArchivingOrReturnTerminal(runId);
        if (isTerminal(run.getStatus())) {
            return get(runId);
        }

        List<YagaBatchArchiveJob> pending =
                jobRepository.findAllByRunIdOrderBySelectionOrderAsc(runId)
                        .stream()
                        .filter(job ->
                                job.getStatus() ==
                                        YagaBatchArchiveJobStatus.SELECTED ||
                                        job.getStatus() ==
                                                YagaBatchArchiveJobStatus.ARCHIVING
                        )
                        .toList();

        for (YagaBatchArchiveJob job : pending) {
            processJob(job.getId());
        }

        completeRun(runId);
        return get(runId);
    }

    private YagaBatchArchiveRun markArchivingOrReturnTerminal(UUID runId) {
        YagaBatchArchiveRun run = transactionTemplate.execute(status -> {
            YagaBatchArchiveRun locked = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaBatchArchiveRunNotFoundException(runId)
                    );
            if (isTerminal(locked.getStatus())) {
                return locked;
            }
            if (locked.getStatus() !=
                    YagaBatchArchiveRunStatus.AWAITING_CONFIRMATION &&
                    locked.getStatus() !=
                            YagaBatchArchiveRunStatus.ARCHIVING) {
                throw new YagaBatchArchiveInvalidStateException(
                        "Yaga batch archive run is not awaiting confirmation"
                );
            }
            locked.setStatus(YagaBatchArchiveRunStatus.ARCHIVING);
            if (locked.getStartedAt() == null) {
                locked.setStartedAt(clock.instant());
            }
            return runRepository.saveAndFlush(locked);
        });
        if (run == null) {
            throw new IllegalStateException("Yaga batch archive run not loaded");
        }
        return run;
    }

    private void processJob(UUID jobId) {
        YagaBatchArchiveJob snapshot = markJobArchiving(jobId);
        if (snapshot == null || isTerminal(snapshot.getStatus())) {
            return;
        }

        Long listingId = snapshot.getMarketplaceListing().getId();
        try {
            MarketplaceListing current = loadListingWithImages(listingId);
            if (isFullyLinked(current)) {
                markAlreadyArchived(jobId, current.getImages().size());
                return;
            }
            if (!isEligible(current)) {
                markFailed(
                        jobId,
                        "LISTING_NOT_ELIGIBLE",
                        "Yaga listing is no longer eligible for image archive",
                        missingImageCount(current)
                );
                return;
            }

            YagaArchiveImagesResponse response =
                    archiveService.archiveImages(listingId);
            if (response.archivedImageCount() == 0 &&
                    response.alreadyLinkedImageCount() ==
                            response.totalImageCount()) {
                markAlreadyArchived(jobId, response.alreadyLinkedImageCount());
            } else {
                markArchived(jobId, response);
            }
        } catch (YagaImageArchiveException exception) {
            markFailed(
                    jobId,
                    exception.getCode().name(),
                    exception.getSafeMessage(),
                    exception.getFailedImageCount().orElse(0)
            );
        } catch (RuntimeException exception) {
            markFailed(
                    jobId,
                    "ARCHIVE_RESULT_UNKNOWN",
                    safeMessage(exception),
                    0
            );
        }
    }

    private YagaBatchArchiveJob markJobArchiving(UUID jobId) {
        return transactionTemplate.execute(status -> {
            YagaBatchArchiveJob job = jobRepository.findByIdForUpdate(jobId)
                    .orElseThrow();
            if (isTerminal(job.getStatus())) {
                return job;
            }
            job.setStatus(YagaBatchArchiveJobStatus.ARCHIVING);
            if (job.getStartedAt() == null) {
                job.setStartedAt(clock.instant());
            }
            job.setAttemptCount(job.getAttemptCount() + 1);
            return jobRepository.saveAndFlush(job);
        });
    }

    private MarketplaceListing loadListingWithImages(Long listingId) {
        return transactionTemplate.execute(status ->
                listingRepository.findByIdWithImagesAndProductImages(listingId)
                        .orElseThrow(() ->
                                new MarketplaceListingNotFoundException(
                                        listingId
                                )
                        )
        );
    }

    private void markArchived(
            UUID jobId,
            YagaArchiveImagesResponse response
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaBatchArchiveJob job = jobRepository.findByIdForUpdate(jobId)
                    .orElseThrow();
            job.setStatus(YagaBatchArchiveJobStatus.ARCHIVED);
            job.setArchivedImageCount(response.archivedImageCount());
            job.setAlreadyLinkedImageCount(response.alreadyLinkedImageCount());
            job.setFailedImageCount(0);
            job.setCompletedAt(clock.instant());
            jobRepository.saveAndFlush(job);
        });
    }

    private void markAlreadyArchived(UUID jobId, int linkedCount) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaBatchArchiveJob job = jobRepository.findByIdForUpdate(jobId)
                    .orElseThrow();
            job.setStatus(YagaBatchArchiveJobStatus.ALREADY_ARCHIVED);
            job.setArchivedImageCount(0);
            job.setAlreadyLinkedImageCount(linkedCount);
            job.setFailedImageCount(0);
            job.setCompletedAt(clock.instant());
            jobRepository.saveAndFlush(job);
        });
    }

    private void markFailed(
            UUID jobId,
            String errorCode,
            String safeMessage,
            int failedImageCount
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaBatchArchiveJob job = jobRepository.findByIdForUpdate(jobId)
                    .orElseThrow();
            job.setStatus(YagaBatchArchiveJobStatus.FAILED);
            job.setLastErrorCode(errorCode);
            job.setLastSafeErrorMessage(safeMessage);
            job.setFailedImageCount(Math.max(0, failedImageCount));
            job.setCompletedAt(clock.instant());
            jobRepository.saveAndFlush(job);
        });
    }

    private void completeRun(UUID runId) {
        transactionTemplate.executeWithoutResult(status -> {
            YagaBatchArchiveRun run = runRepository.findByIdForUpdate(runId)
                    .orElseThrow(() ->
                            new YagaBatchArchiveRunNotFoundException(runId)
                    );
            List<YagaBatchArchiveJob> jobs =
                    jobRepository.findAllByRunIdOrderBySelectionOrderAsc(
                            runId
                    );
            int archived = 0;
            int alreadyArchived = 0;
            int failed = 0;
            for (YagaBatchArchiveJob job : jobs) {
                switch (job.getStatus()) {
                    case ARCHIVED -> archived++;
                    case ALREADY_ARCHIVED -> alreadyArchived++;
                    case FAILED -> failed++;
                    default -> {
                    }
                }
            }
            run.setArchivedListingCount(archived);
            run.setAlreadyArchivedListingCount(alreadyArchived);
            run.setFailedListingCount(failed);
            run.setCompletedAt(clock.instant());
            run.setStatus(failed > 0
                    ? YagaBatchArchiveRunStatus.COMPLETED_WITH_ERRORS
                    : YagaBatchArchiveRunStatus.COMPLETED);
            runRepository.saveAndFlush(run);
        });
    }

    private int resolveMaxListings(Integer requestedMaxListings) {
        int resolved = requestedMaxListings == null
                ? properties.defaultMaxListings()
                : requestedMaxListings;
        if (resolved < 1) {
            throw new YagaBatchArchiveRequestInvalidException(
                    "maxListings must be at least 1"
            );
        }
        if (resolved > properties.maxListings()) {
            throw new YagaBatchArchiveRequestInvalidException(
                    "maxListings must not exceed configured max-listings"
            );
        }
        return resolved;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new YagaBatchArchiveRequestInvalidException(
                    "idempotencyKey must not be blank"
            );
        }
        if (idempotencyKey != null && idempotencyKey.length() > 120) {
            throw new YagaBatchArchiveRequestInvalidException(
                    "idempotencyKey must be at most 120 characters"
            );
        }
    }

    private boolean isEligible(MarketplaceListing listing) {
        return listing.getMarketplace() == Marketplace.YAGA &&
                listing.getStatus() == MarketplaceListingStatus.PUBLISHED &&
                listing.isCurrent() &&
                listing.getProduct().getStatus() != ProductStatus.ARCHIVED &&
                !listing.getImages().isEmpty() &&
                missingImageCount(listing) > 0;
    }

    private boolean isFullyLinked(MarketplaceListing listing) {
        return !listing.getImages().isEmpty() && missingImageCount(listing) == 0;
    }

    private int linkedImageCount(MarketplaceListing listing) {
        return (int) listing.getImages()
                .stream()
                .filter(image -> image.getProductImage() != null)
                .count();
    }

    private int missingImageCount(MarketplaceListing listing) {
        return (int) listing.getImages()
                .stream()
                .filter(image -> image.getProductImage() == null)
                .count();
    }

    private boolean isTerminal(YagaBatchArchiveRunStatus status) {
        return status == YagaBatchArchiveRunStatus.COMPLETED ||
                status == YagaBatchArchiveRunStatus.COMPLETED_WITH_ERRORS ||
                status == YagaBatchArchiveRunStatus.FAILED ||
                status == YagaBatchArchiveRunStatus.CANCELLED;
    }

    private boolean isTerminal(YagaBatchArchiveJobStatus status) {
        return status == YagaBatchArchiveJobStatus.ARCHIVED ||
                status == YagaBatchArchiveJobStatus.ALREADY_ARCHIVED ||
                status == YagaBatchArchiveJobStatus.FAILED;
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private YagaBatchArchiveRunResponse toResponse(
            YagaBatchArchiveRun run
    ) {
        List<YagaBatchArchiveJobResponse> jobs = run.getJobs()
                .stream()
                .sorted(Comparator.comparingInt(
                        YagaBatchArchiveJob::getSelectionOrder
                ))
                .map(this::toResponse)
                .toList();

        return new YagaBatchArchiveRunResponse(
                run.getId(),
                run.getStatus(),
                run.getRequestedMaxListings(),
                run.getSelectedJobCount(),
                run.getArchivedListingCount(),
                run.getAlreadyArchivedListingCount(),
                run.getFailedListingCount(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                jobs
        );
    }

    private YagaBatchArchiveJobResponse toResponse(
            YagaBatchArchiveJob job
    ) {
        return new YagaBatchArchiveJobResponse(
                job.getId(),
                job.getSelectionOrder(),
                job.getProduct().getId(),
                job.getMarketplaceListing().getId(),
                job.getProduct().getTitle(),
                job.getExpectedImageCount(),
                job.getInitiallyLinkedImageCount(),
                job.getStatus(),
                job.getArchivedImageCount(),
                job.getAlreadyLinkedImageCount(),
                job.getFailedImageCount(),
                job.getAttemptCount(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getLastErrorCode(),
                job.getLastSafeErrorMessage()
        );
    }
}
