package ee.nikolas.resalepilot.service;

import ee.nikolas.resalepilot.dto.YagaListingDraftData;
import ee.nikolas.resalepilot.dto.YagaPrepareFormResponse;
import ee.nikolas.resalepilot.entity.MarketplaceListing;
import ee.nikolas.resalepilot.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.entity.Product;
import ee.nikolas.resalepilot.entity.ProductImage;
import ee.nikolas.resalepilot.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.exception.YagaPreparationAlreadyRunningException;
import ee.nikolas.resalepilot.exception.YagaPublishingDataInvalidException;
import ee.nikolas.resalepilot.exception.YagaPublishingDriveDownloadException;
import ee.nikolas.resalepilot.exception.YagaPublishingFormException;
import ee.nikolas.resalepilot.repository.MarketplaceListingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(
        name = "yaga.publishing.enabled",
        havingValue = "true"
)
public class YagaPublishingService {

    public static final String FILLED_NOT_PUBLISHED =
            "FILLED_NOT_PUBLISHED";

    private final MarketplaceListingRepository listingRepository;
    private final GoogleDriveService googleDriveService;
    private final YagaBrowserAutomation browserAutomation;
    private final TransactionTemplate readOnlyTransaction;
    private final AtomicBoolean preparationRunning =
            new AtomicBoolean(false);

    public YagaPublishingService(
            MarketplaceListingRepository listingRepository,
            GoogleDriveService googleDriveService,
            YagaBrowserAutomation browserAutomation,
            PlatformTransactionManager transactionManager
    ) {
        this.listingRepository = listingRepository;
        this.googleDriveService = googleDriveService;
        this.browserAutomation = browserAutomation;

        this.readOnlyTransaction =
                new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.readOnlyTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED
        );
    }

    public YagaPrepareFormResponse prepareForm(Long listingId) {
        if (!preparationRunning.compareAndSet(false, true)) {
            throw new YagaPreparationAlreadyRunningException();
        }

        List<DownloadedDriveFile> downloadedFiles = List.of();

        try {
            YagaListingDraftData draft = loadDraft(listingId);
            downloadedFiles = downloadImages(draft);

            List<YagaPreparedImageFile> imageFiles =
                    toPreparedImageFiles(
                            draft,
                            downloadedFiles
                    );

            YagaFormFillResult result =
                    prepareBrowserForm(draft, imageFiles);

            validateFormResult(draft, result);

            return new YagaPrepareFormResponse(
                    draft.listingId(),
                    draft.productId(),
                    result.imageCount(),
                    result.descriptionFilled(),
                    List.copyOf(result.categoryPath()),
                    draft.condition(),
                    result.price(),
                    result.screenshotPath().toString(),
                    FILLED_NOT_PUBLISHED
            );

        } finally {
            closeDownloadedFiles(downloadedFiles);
            preparationRunning.set(false);
        }
    }

    private YagaFormFillResult prepareBrowserForm(
            YagaListingDraftData draft,
            List<YagaPreparedImageFile> imageFiles
    ) {
        try {
            return browserAutomation.prepareForm(
                    draft,
                    imageFiles
            );

        } catch (YagaPublishingFormException |
                 YagaPublishingDataInvalidException exception) {
            throw exception;

        } catch (RuntimeException exception) {
            throw new YagaPublishingFormException(
                    "Failed to prepare Yaga listing form",
                    exception
            );
        }
    }

    YagaListingDraftData loadDraft(Long listingId) {
        YagaListingDraftData draft =
                readOnlyTransaction.execute(status -> {
                    MarketplaceListing listingWithCategories =
                            listingRepository
                                    .findByIdWithCategories(listingId)
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    listingId
                                            )
                                    );

                    MarketplaceListing listingWithImages =
                            listingRepository
                                    .findByIdWithImagesAndProductImages(
                                            listingId
                                    )
                                    .orElseThrow(() ->
                                            new MarketplaceListingNotFoundException(
                                                    listingId
                                            )
                                    );

                    return toDraft(
                            listingWithCategories,
                            listingWithImages
                    );
                });

        if (draft == null) {
            throw new IllegalStateException(
                    "Yaga publishing snapshot transaction returned no result"
            );
        }

        validateDraft(draft);
        return draft;
    }

    private YagaListingDraftData toDraft(
            MarketplaceListing listingWithCategories,
            MarketplaceListing listingWithImages
    ) {
        Product product = listingWithCategories.getProduct();

        List<String> categoryPath =
                listingWithCategories.getCategories()
                        .stream()
                        .sorted(
                                Comparator.comparingInt(
                                        MarketplaceListingCategory::getCategoryLevel
                                )
                        )
                        .map(MarketplaceListingCategory::getTitle)
                        .toList();

        List<YagaListingDraftData.Image> images =
                listingWithImages.getImages()
                        .stream()
                        .sorted(
                                Comparator.comparingInt(
                                        MarketplaceListingImage::getDisplayOrder
                                )
                        )
                        .map(MarketplaceListingImage::getProductImage)
                        .map(this::toDraftImage)
                        .toList();

        return new YagaListingDraftData(
                listingWithCategories.getId(),
                product.getId(),
                product.getDescription(),
                product.getAskingPrice(),
                "EUR",
                product.getCondition(),
                categoryPath,
                images
        );
    }

    private YagaListingDraftData.Image toDraftImage(
            ProductImage productImage
    ) {
        if (productImage == null) {
            throw new YagaPublishingDataInvalidException(
                    "All Yaga listing images must be archived before preparing the form"
            );
        }

        return new YagaListingDraftData.Image(
                productImage.getDriveFileId(),
                productImage.getFileName(),
                productImage.getDisplayOrder(),
                productImage.isPrimaryImage()
        );
    }

    private void validateDraft(YagaListingDraftData draft) {
        if (isBlank(draft.description())) {
            throw new YagaPublishingDataInvalidException(
                    "Product description is required for Yaga publishing"
            );
        }

        if (draft.askingPrice() == null ||
                draft.askingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new YagaPublishingDataInvalidException(
                    "Product asking price must be positive for Yaga publishing"
            );
        }

        if (draft.categoryPath().isEmpty() ||
                draft.categoryPath().stream().anyMatch(this::isBlank)) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga category path is required"
            );
        }

        YagaConditionMapper.toYaga(draft.condition());

        if (draft.images().isEmpty() || draft.images().size() > 6) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga publishing requires 1 to 6 archived product images"
            );
        }

        for (YagaListingDraftData.Image image : draft.images()) {
            if (isBlank(image.driveFileId())) {
                throw new YagaPublishingDataInvalidException(
                        "Product image Drive file id is required"
                );
            }
        }
    }

    List<DownloadedDriveFile> downloadImages(
            YagaListingDraftData draft
    ) {
        List<DownloadedDriveFile> downloadedFiles =
                new ArrayList<>();

        try {
            List<YagaListingDraftData.Image> orderedImages =
                    draft.images()
                    .stream()
                    .sorted(
                            Comparator.comparingInt(
                                    YagaListingDraftData.Image::displayOrder
                            )
                    )
                    .toList();

            for (YagaListingDraftData.Image image : orderedImages) {
                downloadedFiles.add(
                        googleDriveService.downloadToTemporaryFile(
                                image.driveFileId()
                        )
                );
            }

            return downloadedFiles;

        } catch (RuntimeException exception) {
            closeDownloadedFiles(downloadedFiles);

            if (exception instanceof GoogleDriveAccessException) {
                throw new YagaPublishingDriveDownloadException(
                        "Failed to download product images from Google Drive",
                        exception
                );
            }

            throw new YagaPublishingDriveDownloadException(
                    "Failed to prepare product images from Google Drive",
                    exception
            );
        }
    }

    List<YagaPreparedImageFile> toPreparedImageFiles(
            YagaListingDraftData draft,
            List<DownloadedDriveFile> downloadedFiles
    ) {
        List<YagaListingDraftData.Image> orderedImages =
                draft.images()
                        .stream()
                        .sorted(
                                Comparator.comparingInt(
                                        YagaListingDraftData.Image::displayOrder
                                )
                        )
                        .toList();

        if (orderedImages.size() != downloadedFiles.size()) {
            throw new IllegalStateException(
                    "Downloaded image count does not match draft image count"
            );
        }

        return java.util.stream.IntStream
                .range(0, orderedImages.size())
                .mapToObj(index -> {
                    YagaListingDraftData.Image image =
                            orderedImages.get(index);
                    DownloadedDriveFile file =
                            downloadedFiles.get(index);

                    return new YagaPreparedImageFile(
                            image.driveFileId(),
                            image.fileName(),
                            image.displayOrder(),
                            image.primary(),
                            file.path()
                    );
                })
                .toList();
    }

    void validateFormResult(
            YagaListingDraftData draft,
            YagaFormFillResult result
    ) {
        if (!result.descriptionFilled()) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form description was not confirmed in DOM"
            );
        }

        if (result.imageCount() != draft.images().size()) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form image upload was not confirmed in DOM"
            );
        }

        if (!draft.categoryPath().equals(result.categoryPath())) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form category path was not confirmed in DOM"
            );
        }

        YagaConditionSelection expectedCondition =
                YagaConditionMapper.toYaga(draft.condition());

        if (!expectedCondition.label()
                .equals(result.conditionLabel())) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form condition was not confirmed in DOM"
            );
        }

        if (result.price() == null ||
                result.price().compareTo(draft.askingPrice()) != 0) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form price was not confirmed in DOM"
            );
        }

        if (result.screenshotPath() == null) {
            throw new YagaPublishingDataInvalidException(
                    "Yaga form screenshot was not created"
            );
        }
    }

    void closeDownloadedFiles(
            List<DownloadedDriveFile> downloadedFiles
    ) {
        for (DownloadedDriveFile file : downloadedFiles) {
            try {
                file.close();
            } catch (Exception ignored) {
                // Best-effort cleanup of operation-local temp files.
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
