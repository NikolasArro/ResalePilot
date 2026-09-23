package ee.nikolas.resalepilot.marketplace.controller;

import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import ee.nikolas.resalepilot.marketplace.dto.MarketplaceListingStatusResponse;
import ee.nikolas.resalepilot.marketplace.dto.YagaListingReconciliationResponse;
import ee.nikolas.resalepilot.marketplace.entity.MarketplaceListingStatus;
import ee.nikolas.resalepilot.marketplace.service.YagaListingLocalStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class YagaListingLocalStateControllerTest {

    @Mock
    private YagaListingLocalStateService service;

    @Test
    void statusEndpointUpdatesByAccountAndProductSlug() throws Exception {
        when(service.updateStatusBySlug(
                2L,
                "some-product",
                MarketplaceListingStatus.SOLD
        )).thenReturn(new MarketplaceListingStatusResponse(
                2L,
                11L,
                "9001",
                "some-product",
                MarketplaceListingStatus.SOLD,
                false
        ));

        mvc().perform(patch(
                        "/api/yaga/accounts/{accountId}/listings/by-slug/{productSlug}/status",
                        2L,
                        "some-product"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SOLD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.productSlug").value("some-product"))
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.current").value(false));

        verify(service).updateStatusBySlug(
                2L,
                "some-product",
                MarketplaceListingStatus.SOLD
        );
    }

    @Test
    void reconcileEndpointReturnsSummary() throws Exception {
        when(service.reconcile(2L))
                .thenReturn(new YagaListingReconciliationResponse(
                        2L,
                        "w-a-k-a",
                        261,
                        275,
                        261,
                        0,
                        0,
                        0,
                        14,
                        245,
                        10,
                        5,
                        java.util.List.of(),
                        java.util.List.of()
                ));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/reconcile-listings",
                        2L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.shopSlug").value("w-a-k-a"))
                .andExpect(jsonPath("$.discoveredPublished").value(261))
                .andExpect(jsonPath("$.localCurrentPublished").value(275))
                .andExpect(jsonPath("$.unavailable").value(14))
                .andExpect(jsonPath("$.remotePublishedAndLocalCurrentPublished")
                        .value(245))
                .andExpect(jsonPath("$.remotePublishedButLocalNonCurrent")
                        .value(10))
                .andExpect(jsonPath("$.remotePublishedButMissingLocally")
                        .value(5));

        verify(service).reconcile(2L);
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new YagaListingLocalStateController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
