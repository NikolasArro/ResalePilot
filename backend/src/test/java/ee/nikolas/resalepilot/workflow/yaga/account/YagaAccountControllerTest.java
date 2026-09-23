package ee.nikolas.resalepilot.workflow.yaga.account;

import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class YagaAccountControllerTest {

    @Mock
    private YagaAccountService service;

    @Test
    void patchAllowsDriveFolderOnlyWithoutNameAndShopSlug() throws Exception {
        when(service.patch(eq(2L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new YagaAccountResponse(
                        2L,
                        "Second Shop",
                        "second-shop",
                        "auth/second.json",
                        "drive-folder-2",
                        true,
                        true,
                        5,
                        null,
                        null
                ));

        mvc().perform(patch("/api/yaga/accounts/{id}", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driveFolderId":"drive-folder-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driveFolderId")
                        .value("drive-folder-2"))
                .andExpect(jsonPath("$.name").value("Second Shop"))
                .andExpect(jsonPath("$.shopSlug").value("second-shop"));

        ArgumentCaptor<YagaAccountPatchRequest> captor =
                ArgumentCaptor.forClass(YagaAccountPatchRequest.class);
        verify(service).patch(eq(2L), captor.capture());
        assertThat(captor.getValue().driveFolderId())
                .isEqualTo("drive-folder-2");
        assertThat(captor.getValue().name()).isNull();
        assertThat(captor.getValue().shopSlug()).isNull();
        verifyNoMoreInteractions(service);
    }

    @Test
    void putStillRequiresNameAndShopSlug() throws Exception {
        mvc().perform(put("/api/yaga/accounts/{id}", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driveFolderId":"drive-folder-2"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name")
                        .value("must not be blank"))
                .andExpect(jsonPath("$.validationErrors.shopSlug")
                        .value("must not be blank"));

        verifyNoMoreInteractions(service);
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new YagaAccountController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
