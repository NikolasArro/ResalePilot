package ee.nikolas.resalepilot.workflow.yaga.reconciliation;

import ee.nikolas.resalepilot.workflow.yaga.reconciliation.dto.YagaManualListingReconcileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class YagaManualListingReconciliationControllerTest {
    @Test
    void routesTheExplicitManualListingIdentity() throws Exception {
        var service = mock(YagaManualListingReconciliationService.class);
        when(service.reconcile(eq(2L), eq(332L), any()))
                .thenReturn(new YagaManualListingReconcileResponse(2L, 332L, 535L,
                        "31403739", List.of(340L, 532L, 533L, 534L), true));
        var mvc = MockMvcBuilders.standaloneSetup(
                new YagaManualListingReconciliationController(service)).build();

        mvc.perform(post("/api/yaga/accounts/2/products/332/reconcile-manual-listing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceListingId":340,"manualExternalListingId":31403739,
                                 "publicUrl":"https://www.yaga.ee/w-a-k-a/toode/aovhl8bsh8c"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("currentListingId").value(535))
                .andExpect(jsonPath("externalListingId").value("31403739"));
        verify(service).reconcile(eq(2L), eq(332L), argThat(request ->
                request.sourceListingId().equals(340L) &&
                        request.manualExternalListingId().equals(31403739L) &&
                        request.publicUrl().equals("https://www.yaga.ee/w-a-k-a/toode/aovhl8bsh8c")));
    }
}
