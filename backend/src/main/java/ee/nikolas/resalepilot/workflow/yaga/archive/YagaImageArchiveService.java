package ee.nikolas.resalepilot.workflow.yaga.archive;

import ee.nikolas.resalepilot.integration.drive.model.ArchivedDriveFile;
import ee.nikolas.resalepilot.integration.drive.storage.DriveArchiveStorage;
import ee.nikolas.resalepilot.marketplace.entity.Marketplace;

import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchiveImagesResponse;
import ee.nikolas.resalepilot.workflow.yaga.archive.dto.YagaArchivedImageResponse;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.marketplace.exception.MarketplaceListingNotFoundException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.integration.yaga.model.DownloadedYagaImage;
import ee.nikolas.resalepilot.integration.yaga.downloader.YagaImageDownloader;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class YagaImageArchiveService {

    private final MarketplaceListingRepository listingRepository;
    private final ProductImageRepository productImageRepository;
    private final YagaImageDownloader imageDownloader;
    private final DriveArchiveStorage driveArchiveStorage;
    private final TransactionTemplate transactionTemplate;

    public YagaImageArchiveService(
            MarketplaceListingRepository listingRepository,
            ProductImageRepository productImageRepository,
            YagaImageDownloader imageDownloader,
            DriveArchiveStorage driveArchiveStorage,
            PlatformTransactionManager transactionManager
    ) {
        this.listingRepository = listingRepository;
        this.productImageRepository = productImageRepository;
        this.imageDownloader = imageDownloader;
        this.driveArchiveStorage = driveArchiveStorage;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    public YagaArchiveImagesResponse archiveImages(
            Long marketplaceListingId
    ) {
        MarketplaceListing snapshot =
                transactionTemplate.execute(status ->
                        listingRepository.findByIdWithImages(
                                        marketplaceListingId
                                )
                                .orElseThrow(() ->
                                        new MarketplaceListingNotFoundException(
                                                marketplaceListingId
                                        )
                                )
                );

        if (snapshot == null) {
            throw new MarketplaceListingNotFoundException(
                    marketplaceListingId
            );
        }

        List<MarketplaceListingImage> missingImages =
                snapshot.getImages()
                        .stream()
                        .filter(image -> image.getProductImage() == null)
                        .sorted(
                                Comparator.comparingInt(
                                        MarketplaceListingImage::getDisplayOrder
                                )
                        )
                        .toList();

        int alreadyLinkedCount =
                snapshot.getImages().size() -
                        missingImages.size();

        if (missingImages.isEmpty()) {
            return new YagaArchiveImagesResponse(
                    marketplaceListingId,
                    alreadyLinkedCount,
                    0,
                    snapshot.getImages().size(),
                    imageResponses(snapshot)
            );
        }

        driveArchiveStorage.verifyAvailable();

        List<YagaImportedProductData.Image> imagesToDownload =
                missingImages.stream()
                        .map(image ->
                                new YagaImportedProductData.Image(
                                        image.getExternalImageId(),
                                        image.getSourceUrl(),
                                        image.getFileName()
                                )
                        )
                        .toList();

        List<DownloadedYagaImage> downloadedImages =
                imageDownloader.downloadAll(imagesToDownload);

        List<ArchivedDriveFile> archivedDriveFiles;

        try {
            archivedDriveFiles =
                    driveArchiveStorage.uploadYagaImages(
                            snapshot.getProduct().getSku(),
                            marketplaceListingId,
                            downloadedImages
                    );

        } finally {
            closeDownloadedImages(downloadedImages);
        }

        try {
            PersistArchiveResult result = persistArchivedImages(
                    marketplaceListingId,
                    archivedDriveFiles
            );

            driveArchiveStorage.deleteCreatedFiles(
                    result.unusedDriveFileIds()
            );

            return result.response();

        } catch (RuntimeException exception) {
            driveArchiveStorage.deleteCreatedFiles(
                    archivedDriveFiles.stream()
                            .map(ArchivedDriveFile::driveFileId)
                            .toList()
            );

            throw exception;
        }
    }

    private PersistArchiveResult persistArchivedImages(
            Long marketplaceListingId,
            List<ArchivedDriveFile> archivedDriveFiles
    ) {
        PersistArchiveResult result =
                transactionTemplate.execute(status -> {
                    MarketplaceListing listing = listingRepository
                            .findByIdWithImagesForUpdate(
                                    marketplaceListingId
                            )
                            .orElseThrow(() ->
                                    new MarketplaceListingNotFoundException(
                                            marketplaceListingId
                                    )
                            );

                    Map<String, ArchivedDriveFile> archivedByExternalId =
                            new LinkedHashMap<>();

                    archivedDriveFiles.forEach(file ->
                            archivedByExternalId.put(
                                    file.externalImageId(),
                                    file
                            )
                    );

                    Product product = listing.getProduct();

                    int nextDisplayOrder = productImageRepository
                            .findFirstByProductIdOrderByDisplayOrderDesc(
                                    product.getId()
                            )
                            .map(image ->
                                    image.getDisplayOrder() + 1
                            )
                            .orElse(0);

                    boolean hasPrimary = productImageRepository
                            .findByProductIdAndPrimaryImageTrue(
                                    product.getId()
                            )
                            .isPresent();

                    int alreadyLinked = 0;
                    int archived = 0;

                    List<MarketplaceListingImage> orderedImages =
                            listing.getImages()
                                    .stream()
                                    .sorted(
                                            Comparator.comparingInt(
                                                    MarketplaceListingImage::getDisplayOrder
                                            )
                                    )
                                    .toList();

                    for (MarketplaceListingImage listingImage :
                            orderedImages) {

                        if (listingImage.getProductImage() != null) {
                            alreadyLinked++;
                            continue;
                        }

                        ArchivedDriveFile archivedFile =
                                archivedByExternalId.get(
                                        listingImage.getExternalImageId()
                                );

                        if (archivedFile == null) {
                            continue;
                        }

                        ProductImage productImage =
                                new ProductImage(
                                        product,
                                        archivedFile.driveFileId()
                                );

                        productImage.setFileName(
                                archivedFile.originalFileName()
                        );
                        productImage.setDisplayOrder(nextDisplayOrder++);
                        productImage.setPrimaryImage(!hasPrimary);

                        if (!hasPrimary) {
                            hasPrimary = true;
                        }

                        ProductImage savedProductImage =
                                productImageRepository.save(productImage);

                        listingImage.setProductImage(savedProductImage);
                        archivedByExternalId.remove(
                                listingImage.getExternalImageId()
                        );
                        archived++;
                    }

                    List<String> unusedDriveFileIds =
                            archivedByExternalId.values()
                                    .stream()
                                    .map(ArchivedDriveFile::driveFileId)
                                    .toList();

                    return new PersistArchiveResult(
                            new YagaArchiveImagesResponse(
                                    marketplaceListingId,
                                    alreadyLinked,
                                    archived,
                                    listing.getImages().size(),
                                    imageResponses(listing)
                            ),
                            unusedDriveFileIds
                    );
                });

        if (result == null) {
            throw new IllegalStateException(
                    "Yaga image archive transaction returned no result"
            );
        }

        return result;
    }

    private List<YagaArchivedImageResponse> imageResponses(
            MarketplaceListing listing
    ) {
        return listing.getImages()
                .stream()
                .sorted(
                        Comparator.comparingInt(
                                MarketplaceListingImage::getDisplayOrder
                        )
                )
                .map(this::imageResponse)
                .toList();
    }

    private YagaArchivedImageResponse imageResponse(
            MarketplaceListingImage listingImage
    ) {
        ProductImage productImage =
                listingImage.getProductImage();

        return new YagaArchivedImageResponse(
                listingImage.getId(),
                listingImage.getExternalImageId(),
                listingImage.getSourceUrl(),
                productImage == null ? null : productImage.getId(),
                productImage == null ? null : productImage.getDriveFileId(),
                productImage == null ? null : productImage.getFileName(),
                productImage == null ? listingImage.getDisplayOrder() :
                        productImage.getDisplayOrder(),
                productImage != null && productImage.isPrimaryImage()
        );
    }

    private void closeDownloadedImages(
            List<DownloadedYagaImage> downloadedImages
    ) {
        for (DownloadedYagaImage image : downloadedImages) {
            try {
                image.close();
            } catch (Exception ignored) {
                // Best-effort cleanup of operation-local temp files.
            }
        }
    }

    private record PersistArchiveResult(
            YagaArchiveImagesResponse response,
            List<String> unusedDriveFileIds
    ) {
    }
}
