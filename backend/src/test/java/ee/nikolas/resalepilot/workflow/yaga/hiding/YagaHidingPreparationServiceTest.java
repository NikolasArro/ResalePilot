package ee.nikolas.resalepilot.workflow.yaga.hiding;

import ee.nikolas.resalepilot.marketplace.entity.Marketplace;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListing;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingCategory;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingImage;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.product.entity.Product;
import ee.nikolas.resalepilot.product.entity.ProductCondition;
import ee.nikolas.resalepilot.product.entity.ProductImage;
import ee.nikolas.resalepilot.workflow.yaga.common.YagaConfirmationTokenService;
import ee.nikolas.resalepilot.workflow.yaga.hiding.automation.YagaHidingBrowserAutomation;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideControlInspection;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHideTargetDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingDraftData;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaHidingPreparedBrowserSession;
import ee.nikolas.resalepilot.workflow.yaga.hiding.model.YagaManagementControlDiagnostic;

import ee.nikolas.resalepilot.workflow.yaga.hiding.config.YagaHidingProperties;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidePreparationResponse;
import ee.nikolas.resalepilot.workflow.yaga.hiding.dto.YagaHidingStatus;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingAuthException;
import ee.nikolas.resalepilot.workflow.yaga.hiding.exception.YagaHidingPreconditionException;
import ee.nikolas.resalepilot.integration.yaga.exception.YagaImportException;
import ee.nikolas.resalepilot.workflow.yaga.reconciliation.exception.YagaPublicationReconciliationConflictException;
import ee.nikolas.resalepilot.marketplace.repository.MarketplaceListingRepository;
import ee.nikolas.resalepilot.product.repository.ProductImageRepository;
import ee.nikolas.resalepilot.integration.yaga.model.YagaImportedProductData;
import ee.nikolas.resalepilot.integration.yaga.client.YagaPageDataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;

@ExtendWith(MockitoExtension.class)
class YagaHidingPreparationServiceTest {

    @Mock
    private MarketplaceListingRepository listingRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private YagaPageDataClient pageDataClient;

    @Mock
    private YagaHidingBrowserAutomation browserAutomation;

    @Mock
    private PlatformTransactionManager transactionManager;

    private YagaHidingPreparationService service;
    private final Instant fixedNow =
            Instant.parse("2026-09-04T18:00:00Z");
    private final YagaHidingPreparedBrowserSession browserSession =
            () -> new YagaHidingDraftData(
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

    @BeforeEach
    void setUp() {
        YagaHidingProperties properties =
                new YagaHidingProperties();
        service = new YagaHidingPreparationService(
                listingRepository,
                productImageRepository,
                pageDataClient,
                browserAutomation,
                new YagaConfirmationTokenService(),
                properties,
                transactionManager,
                Clock.fixed(fixedNow, ZoneOffset.UTC)
        );
        lenient().when(transactionManager.getTransaction(
                any(TransactionDefinition.class)
        )).thenReturn(new SimpleTransactionStatus());
        lenient().when(browserAutomation.prepareSession(any()))
                .thenReturn(browserSession);
    }

    @Test
    void prepareHideReturnsAwaitingConfirmationAfterSafeInspection() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(readyInspection(oldListing));

        YagaHidePreparationResponse response =
                service.prepareHide(oldListing.getId());

        assertThat(response.status())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(response.oldListingId())
                .isEqualTo(oldListing.getId());
        assertThat(response.newListingId())
                .isEqualTo(newListing.getId());
        assertThat(response.confirmationToken()).isNotBlank();
        assertThat(response.readyForConfirmation()).isTrue();

        ArgumentCaptor<YagaHidingDraftData> draftCaptor =
                ArgumentCaptor.captor();
        verify(browserAutomation).prepareSession(draftCaptor.capture());
        verify(browserAutomation).inspectHideControl(browserSession);
        verify(browserAutomation).closeSession(browserSession);
        assertThat(draftCaptor.getValue().oldListingId())
                .isEqualTo(oldListing.getId());
        assertThat(draftCaptor.getValue().oldProductSlug())
                .isEqualTo("ip7p454fe6o");
        verify(listingRepository, never()).save(any());
        verify(listingRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingReplacementPublishedListingFailsBeforeBrowser() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        when(listingRepository.findByIdWithImagesAndProductImages(1L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findAllByProductIdAndMarketplaceAndStatus(
                product.getId(),
                Marketplace.YAGA,
                MarketplaceListingStatus.PUBLISHED
        )).thenReturn(List.of(oldListing));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingPreconditionException.class)
                .hasMessageContaining("replacement");

        verifyNoInteractions(pageDataClient, browserAutomation);
    }

    @Test
    void unconfirmedReplacementDataFailsBeforeBrowser() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(new YagaImportedProductData(
                        30796018L,
                        "nik-ar",
                        "5u7arpkm6q",
                        "Different",
                        new BigDecimal("17.00"),
                        "EUR",
                        "published",
                        new YagaImportedProductData.Condition(3L, "Hea"),
                        categories(),
                        images(4),
                        Instant.now(),
                        Instant.now(),
                        null,
                        null
                ));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(
                        YagaPublicationReconciliationConflictException.class
                );

        verifyNoInteractions(browserAutomation);
    }

    @Test
    void wrongTargetListingInspectionFails() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(inspection(
                        "https://www.yaga.ee/nik-ar/toode/5u7arpkm6q",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        1,
                        1,
                        1,
                        "Peida",
                        "Peida",
                        "button",
                        "button",
                        true,
                        false,
                        false,
                        false,
                        false
                ));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingPreconditionException.class)
                .hasMessageContaining("old listing");
    }

    @Test
    void expectedManagementUrlWithWrongDomDataFails() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(inspection(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        1,
                        1,
                        1,
                        "Peida",
                        "Peida",
                        "button",
                        "button",
                        true,
                        false,
                        false,
                        true,
                        false
                ));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingPreconditionException.class)
                .satisfies(exception -> {
                    YagaHidingPreconditionException typed =
                            (YagaHidingPreconditionException) exception;
                    assertThat(typed.getDetails())
                            .containsEntry(
                                    "targetEvidenceCount",
                                    "1"
                            );
                    assertThat(typed.getDetails())
                            .containsEntry(
                                    "expectedProductSlugInDom",
                                    "false"
                            );
                });
    }

    @Test
    void twoIndependentTargetSignalsAllowInspectionResponse() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(inspection(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        1,
                        1,
                        1,
                        "Peida",
                        "Peida",
                        "button",
                        "button",
                        true,
                        true,
                        false,
                        true,
                        false
                ));

        YagaHidePreparationResponse response =
                service.prepareHide(1L);

        assertThat(response.readyForConfirmation()).isTrue();
        verify(browserAutomation).inspectHideControl(browserSession);
        verify(listingRepository, never()).save(any());
        verify(listingRepository, never()).saveAndFlush(any());
    }

    @Test
    void replacementWithoutFullImageSetFailsBeforeYagaRead() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        newListing.getImages().removeLast();
        mockValidListings(oldListing, newListing, product);

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingPreconditionException.class)
                .hasMessageContaining("full image set");

        verifyNoInteractions(pageDataClient, browserAutomation);
    }

    @Test
    void editUrlIsRejected() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(inspection(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o/muuda",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        1,
                        1,
                        1,
                        "Peida",
                        "Peida",
                        "button",
                        "button",
                        true,
                        true,
                        true,
                        false,
                        true
                ));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingPreconditionException.class)
                .satisfies(exception -> {
                    YagaHidingPreconditionException typed =
                            (YagaHidingPreconditionException) exception;
                    assertThat(typed.getDetails())
                            .containsEntry(
                                    "currentUrl",
                                    "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o/muuda"
                            );
                });
    }

    @Test
    void correctTargetWithoutOwnerControlsIsAuthFailure() {
        Product product = product();
        MarketplaceListing oldListing =
                oldListing(product);
        MarketplaceListing newListing =
                newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(browserAutomation.inspectHideControl(browserSession))
                .thenReturn(inspection(
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true,
                        true,
                        true,
                        true,
                        false,
                        false
                ));

        assertThatThrownBy(() -> service.prepareHide(1L))
                .isInstanceOf(YagaHidingAuthException.class)
                .satisfies(exception -> {
                    YagaHidingAuthException typed =
                            (YagaHidingAuthException) exception;
                    assertThat(typed.getDetails())
                            .containsEntry(
                                    "ownerControlsVisible",
                                    "false"
                            )
                            .containsEntry(
                                    "currentUrl",
                                    "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o"
                            );
                });
    }

    @Test
    void yagaNotVisibleStatusIsRecognizedAsHidden() {
        assertThat(service.isHiddenYagaStatus(
                yagaData(
                        "not-visible",
                        "27988552",
                        "ip7p454fe6o",
                        null
                )
        )).isTrue();
    }

    @Test
    void reconcileHiddenListingSwitchesCurrentWithRowLocks() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        MarketplaceListing newListing = newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(yagaData(
                        "not-visible",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        null
                ));
        when(listingRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(newListing));

        var response = service.reconcileHiddenListing(1L);

        assertThat(response.status()).isEqualTo(YagaHidingStatus.HIDDEN);
        assertThat(response.hidden()).isTrue();
        assertThat(oldListing.getStatus())
                .isEqualTo(MarketplaceListingStatus.HIDDEN);
        assertThat(oldListing.isCurrent()).isFalse();
        assertThat(newListing.getStatus())
                .isEqualTo(MarketplaceListingStatus.PUBLISHED);
        assertThat(newListing.isCurrent()).isTrue();
        assertThat(oldListing.getHiddenAt()).isEqualTo(fixedNow);
        InOrder inOrder = inOrder(listingRepository);
        inOrder.verify(listingRepository).saveAndFlush(oldListing);
        inOrder.verify(listingRepository).saveAndFlush(newListing);
        verifyNoInteractions(browserAutomation);
    }

    @Test
    void yagaHiddenAtIsUsedWhenAvailable() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        MarketplaceListing newListing = newListing(product);
        Instant yagaHiddenAt =
                Instant.parse("2026-09-04T17:59:00Z");
        when(listingRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(newListing));

        Instant result = service.markOldHiddenAndNewCurrent(
                draft(),
                yagaHiddenAt
        );

        assertThat(result).isEqualTo(yagaHiddenAt);
        assertThat(oldListing.getHiddenAt()).isEqualTo(yagaHiddenAt);
        assertThat(oldListing.getLastSyncedAt()).isEqualTo(fixedNow);
        assertThat(newListing.getLastSyncedAt()).isEqualTo(fixedNow);
    }

    @Test
    void existingHiddenAtIsPreservedOnRepeatedReconcile() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        MarketplaceListing newListing = newListing(product);
        Instant originalHiddenAt =
                Instant.parse("2026-09-04T17:00:00Z");
        Instant newerYagaHiddenAt =
                Instant.parse("2026-09-04T18:30:00Z");
        oldListing.setStatus(MarketplaceListingStatus.HIDDEN);
        oldListing.setCurrent(false);
        oldListing.setHiddenAt(originalHiddenAt);
        newListing.setCurrent(true);
        when(listingRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(newListing));

        Instant result = service.markOldHiddenAndNewCurrent(
                draft(),
                newerYagaHiddenAt
        );

        assertThat(result).isEqualTo(originalHiddenAt);
        assertThat(oldListing.getHiddenAt()).isEqualTo(originalHiddenAt);
        InOrder inOrder = inOrder(listingRepository);
        inOrder.verify(listingRepository).saveAndFlush(oldListing);
        inOrder.verify(listingRepository).saveAndFlush(newListing);
    }

    @Test
    void repeatedHiddenReconcileDoesNotCreateListingsOrImages() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        oldListing.setStatus(MarketplaceListingStatus.HIDDEN);
        oldListing.setCurrent(false);
        MarketplaceListing newListing = newListing(product);
        newListing.setCurrent(true);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(yagaData(
                        "not-visible",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        Instant.now()
                ));
        when(listingRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(oldListing));
        when(listingRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(newListing));

        service.reconcileHiddenListing(1L);

        verify(listingRepository, never()).save(any(MarketplaceListing.class));
        verify(listingRepository, times(2))
                .saveAndFlush(any(MarketplaceListing.class));
        verifyNoInteractions(browserAutomation);
    }

    @Test
    void unconfirmedOldHiddenStateDoesNotChangeDb() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        MarketplaceListing newListing = newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenReturn(pageData());
        when(pageDataClient.getProduct(oldListing.getExternalUrl()))
                .thenReturn(yagaData(
                        "published",
                        oldListing.getExternalListingId(),
                        oldListing.getProductSlug(),
                        null
                ));

        assertThatThrownBy(() -> service.reconcileHiddenListing(1L))
                .isInstanceOf(
                        YagaPublicationReconciliationConflictException.class
                );

        verify(listingRepository, never()).findByIdForUpdate(any());
        verify(listingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(browserAutomation);
    }

    @Test
    void unavailableReplacementDoesNotChangeDb() {
        Product product = product();
        MarketplaceListing oldListing = oldListing(product);
        MarketplaceListing newListing = newListing(product);
        mockValidListings(oldListing, newListing, product);
        when(pageDataClient.getProduct(newListing.getExternalUrl()))
                .thenThrow(new YagaImportException("Yaga unavailable"));

        assertThatThrownBy(() -> service.reconcileHiddenListing(1L))
                .isInstanceOf(YagaImportException.class);

        verify(pageDataClient, never())
                .getProduct(oldListing.getExternalUrl());
        verify(listingRepository, never()).findByIdForUpdate(any());
        verify(listingRepository, never()).saveAndFlush(any());
        verifyNoInteractions(browserAutomation);
    }

    private void mockValidListings(
            MarketplaceListing oldListing,
            MarketplaceListing newListing,
            Product product
    ) {
        lenient().when(listingRepository
                .findByIdWithImagesAndProductImages(1L))
                .thenReturn(Optional.of(oldListing));
        lenient().when(listingRepository
                .findByIdWithImagesAndProductImages(2L))
                .thenReturn(Optional.of(newListing));
        lenient().when(listingRepository
                .findByIdWithCategories(1L))
                .thenReturn(Optional.of(oldListing));
        lenient().when(listingRepository
                .findByIdWithCategories(2L))
                .thenReturn(Optional.of(newListing));
        lenient().when(listingRepository
                .findAllByProductIdAndMarketplaceAndStatus(
                        product.getId(),
                        Marketplace.YAGA,
                        MarketplaceListingStatus.PUBLISHED
                ))
                .thenReturn(List.of(oldListing, newListing));
        lenient().when(productImageRepository
                .findAllByProductIdOrderByDisplayOrderAsc(
                        product.getId()
                ))
                .thenReturn(productImages(product));
    }

    private Product product() {
        Product product = new Product("BOOK-001", "Kalevipoeg");
        product.setId(10L);
        product.setDescription("Description");
        product.setAskingPrice(new BigDecimal("17.00"));
        product.setCondition(ProductCondition.GOOD);
        return product;
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

    private MarketplaceListing oldListing(Product product) {
        MarketplaceListing listing = listing(
                1L,
                product,
                "27988552",
                "ip7p454fe6o",
                true
        );
        for (int index = 0; index < 4; index++) {
            MarketplaceListingImage listingImage =
                    listingImage(index);
            listingImage.setProductImage(
                    productImage(product, index)
            );
            listing.addImage(listingImage);
        }
        addCategories(listing);
        return listing;
    }

    private MarketplaceListing newListing(Product product) {
        MarketplaceListing listing = listing(
                2L,
                product,
                "30796018",
                "5u7arpkm6q",
                false
        );
        for (int index = 0; index < 4; index++) {
            MarketplaceListingImage listingImage =
                    listingImage(index);
            listingImage.setProductImage(
                    productImage(product, index)
            );
            listing.addImage(listingImage);
        }
        addCategories(listing);
        return listing;
    }

    private MarketplaceListing listing(
            Long id,
            Product product,
            String externalListingId,
            String productSlug,
            boolean current
    ) {
        MarketplaceListing listing =
                new MarketplaceListing(
                        product,
                        Marketplace.YAGA,
                        externalListingId,
                        "https://www.yaga.ee/nik-ar/toode/" +
                                productSlug
                );
        listing.setId(id);
        listing.setShopSlug("nik-ar");
        listing.setProductSlug(productSlug);
        listing.setStatus(MarketplaceListingStatus.PUBLISHED);
        listing.setCurrent(current);
        return listing;
    }

    private void addCategories(MarketplaceListing listing) {
        listing.addCategory(
                new MarketplaceListingCategory(
                        0,
                        1L,
                        null,
                        "Raamatud"
                )
        );
        listing.addCategory(
                new MarketplaceListingCategory(
                        1,
                        2L,
                        1L,
                        "Ajalugu"
                )
        );
    }

    private MarketplaceListingImage listingImage(int index) {
        return new MarketplaceListingImage(
                "image-" + index,
                "https://images.yaga.ee/image-" + index + ".jpg",
                "image-" + index + ".jpg",
                index
        );
    }

    private List<ProductImage> productImages(Product product) {
        return List.of(
                productImage(product, 0),
                productImage(product, 1),
                productImage(product, 2),
                productImage(product, 3)
        );
    }

    private ProductImage productImage(
            Product product,
            int index
    ) {
        ProductImage image =
                new ProductImage(product, "drive-" + index);
        image.setId((long) index + 1);
        image.setDisplayOrder(index);
        image.setPrimaryImage(index == 0);
        return image;
    }

    private YagaImportedProductData pageData() {
        return yagaData(
                "published",
                "30796018",
                "5u7arpkm6q",
                null
        );
    }

    private YagaImportedProductData yagaData(
            String status,
            String externalListingId,
            String productSlug,
            Instant hiddenAt
    ) {
        return new YagaImportedProductData(
                Long.valueOf(externalListingId),
                "nik-ar",
                productSlug,
                "Description",
                new BigDecimal("17.00"),
                "EUR",
                status,
                new YagaImportedProductData.Condition(3L, "Hea"),
                categories(),
                images(4),
                Instant.now(),
                Instant.now(),
                hiddenAt,
                null
        );
    }

    private List<YagaImportedProductData.Category> categories() {
        return List.of(
                new YagaImportedProductData.Category(
                        1L,
                        null,
                        "Raamatud",
                        List.of()
                ),
                new YagaImportedProductData.Category(
                        2L,
                        1L,
                        "Ajalugu",
                        List.of()
                )
        );
    }

    private List<YagaImportedProductData.Image> images(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index ->
                        new YagaImportedProductData.Image(
                                "new-image-" + index,
                                "https://images.yaga.ee/new-" +
                                        index + ".jpg",
                                "new-" + index + ".jpg"
                        )
                )
                .toList();
    }

    private YagaHideControlInspection readyInspection(
            MarketplaceListing oldListing
    ) {
        return inspection(
                "https://www.yaga.ee/nik-ar/toode/" +
                        oldListing.getProductSlug(),
                oldListing.getExternalListingId(),
                oldListing.getProductSlug(),
                1,
                1,
                1,
                "Peida",
                "Peida",
                "button",
                "button",
                true,
                true,
                true,
                true,
                false
        );
    }

    private YagaHideControlInspection inspection(
            String currentUrl,
            String targetExternalListingId,
            String targetProductSlug,
            int candidateCount,
            int visibleCandidateCount,
            int enabledCandidateCount,
            String controlText,
            String accessibleName,
            String tagName,
            String typeAttribute,
            boolean ready,
            boolean productSlugInDom,
            boolean externalListingIdInDom,
            boolean managementUrlContainsExpectedTarget,
            boolean canonicalProductUrlInDom
    ) {
        return inspection(
                currentUrl,
                targetExternalListingId,
                targetProductSlug,
                candidateCount,
                visibleCandidateCount,
                enabledCandidateCount,
                controlText,
                accessibleName,
                tagName,
                typeAttribute,
                ready,
                productSlugInDom,
                externalListingIdInDom,
                managementUrlContainsExpectedTarget,
                canonicalProductUrlInDom,
                true,
                true
        );
    }

    private YagaHideControlInspection inspection(
            String currentUrl,
            String targetExternalListingId,
            String targetProductSlug,
            int candidateCount,
            int visibleCandidateCount,
            int enabledCandidateCount,
            String controlText,
            String accessibleName,
            String tagName,
            String typeAttribute,
            boolean ready,
            boolean productSlugInDom,
            boolean externalListingIdInDom,
            boolean managementUrlContainsExpectedTarget,
            boolean canonicalProductUrlInDom,
            boolean editControlVisible,
            boolean hideControlVisible
    ) {
        return new YagaHideControlInspection(
                currentUrl,
                targetExternalListingId,
                targetProductSlug,
                candidateCount,
                visibleCandidateCount,
                enabledCandidateCount,
                controlText,
                accessibleName,
                tagName,
                typeAttribute,
                ready,
                Instant.now(),
                new YagaHideTargetDiagnostics(
                        1L,
                        "nik-ar",
                        "ip7p454fe6o",
                        "27988552",
                        "https://www.yaga.ee/nik-ar/toode/ip7p454fe6o",
                        currentUrl,
                        "Yaga",
                        "playwright/.auth/yaga-state.json",
                        true,
                        true,
                        100L,
                        productSlugInDom,
                        externalListingIdInDom,
                        canonicalProductUrlInDom,
                        managementUrlContainsExpectedTarget,
                        editControlVisible,
                        hideControlVisible,
                        editControlVisible && hideControlVisible,
                        java.nio.file.Path.of(
                                "playwright/screenshots/test.png"
                        ),
                        List.of(new YagaManagementControlDiagnostic(
                                "button",
                                null,
                                "Peida"
                        ))
                )
        );
    }
}
