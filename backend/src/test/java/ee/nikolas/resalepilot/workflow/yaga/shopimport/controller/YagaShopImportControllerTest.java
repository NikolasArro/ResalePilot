package ee.nikolas.resalepilot.workflow.yaga.shopimport.controller;

import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.dto.YagaAccountBulkImportResponse;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaAccountBulkImportService;
import ee.nikolas.resalepilot.workflow.yaga.shopimport.service.YagaShopImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class YagaShopImportControllerTest {

    @Mock
    private YagaShopImportService importService;

    @Mock
    private YagaAccountBulkImportService accountBulkImportService;

    @Test
    void accountBulkImportAcceptsOptionalLimit() throws Exception {
        when(accountBulkImportService.importCurrentListings(
                2L,
                5,
                null,
                null
        ))
                .thenReturn(new YagaAccountBulkImportResponse(
                        2L,
                        "w-a-k-a",
                        12,
                        12,
                        5,
                        5,
                        0,
                        0,
                        0,
                        List.of()
                ));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/import-listings?limit=5",
                        2L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.shopSlug").value("w-a-k-a"))
                .andExpect(jsonPath("$.discoveredTotal").value(12))
                .andExpect(jsonPath("$.processedCount").value(5))
                .andExpect(jsonPath("$.created").value(5));

        verify(accountBulkImportService)
                .importCurrentListings(2L, 5, null, null);
    }

    @Test
    void accountBulkImportAcceptsOptionalOffset() throws Exception {
        when(accountBulkImportService.importCurrentListings(2L, 5, 5, null))
                .thenReturn(new YagaAccountBulkImportResponse(
                        2L,
                        "w-a-k-a",
                        12,
                        12,
                        5,
                        5,
                        0,
                        0,
                        0,
                        List.of()
                ));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/import-listings?limit=5&offset=5",
                        2L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.processedCount").value(5))
                .andExpect(jsonPath("$.created").value(5));

        verify(accountBulkImportService).importCurrentListings(2L, 5, 5, null);
    }

    @Test
    void accountBulkImportAcceptsOnlyNew() throws Exception {
        when(accountBulkImportService.importCurrentListings(2L, 5, null, true))
                .thenReturn(new YagaAccountBulkImportResponse(
                        2L,
                        "w-a-k-a",
                        12,
                        12,
                        5,
                        5,
                        0,
                        0,
                        0,
                        List.of()
                ));

        mvc().perform(post(
                        "/api/yaga/accounts/{accountId}/import-listings?limit=5&onlyNew=true",
                        2L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.processedCount").value(5))
                .andExpect(jsonPath("$.created").value(5));

        verify(accountBulkImportService)
                .importCurrentListings(2L, 5, null, true);
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new YagaShopImportController(
                        importService,
                        accountBulkImportService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
