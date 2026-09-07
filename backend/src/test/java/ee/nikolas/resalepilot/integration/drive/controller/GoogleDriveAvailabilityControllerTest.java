package ee.nikolas.resalepilot.integration.drive.controller;

import ee.nikolas.resalepilot.common.exception.GlobalExceptionHandler;
import ee.nikolas.resalepilot.integration.drive.config.GoogleDriveProperties;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAuthException;
import ee.nikolas.resalepilot.integration.drive.service.GoogleDriveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GoogleDriveAvailabilityControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-07T07:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private GoogleDriveService googleDriveService;

    @Test
    void verifyAvailableSuccessReturnsCompactResponse() throws Exception {
        MockMvc mvc = mvc();

        mvc.perform(get("/api/drive/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.authMode").value("OAUTH"))
                .andExpect(jsonPath("$.checkedAt")
                        .value("2026-09-07T07:00:00Z"));

        verify(googleDriveService).verifyAvailable();
        verifyNoMoreInteractions(googleDriveService);
    }

    @Test
    void authFailureReturns401WithoutSecrets() throws Exception {
        doThrow(new GoogleDriveAuthException(
                "Google Drive authentication is invalid or expired",
                new RuntimeException("token revoked")
        ))
                .when(googleDriveService)
                .verifyAvailable();

        mvc().perform(get("/api/drive/availability"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("Google Drive authentication is invalid or expired"))
                .andExpect(jsonPath("$.message", not(containsString("token"))));

        verify(googleDriveService).verifyAvailable();
    }

    @Test
    void driveFailureReturns502WithoutSecrets() throws Exception {
        doThrow(new GoogleDriveAccessException(
                "Google Drive availability check failed",
                new RuntimeException("permission denied")
        ))
                .when(googleDriveService)
                .verifyAvailable();

        mvc().perform(get("/api/drive/availability"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message")
                        .value("Google Drive availability check failed"))
                .andExpect(jsonPath("$.message", not(containsString("secret"))))
                .andExpect(jsonPath("$.message", not(containsString("refresh"))));

        verify(googleDriveService).verifyAvailable();
    }

    private MockMvc mvc() {
        return MockMvcBuilders
                .standaloneSetup(new GoogleDriveAvailabilityController(
                        googleDriveService,
                        properties(),
                        CLOCK
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private GoogleDriveProperties properties() {
        return new GoogleDriveProperties(
                true,
                GoogleDriveProperties.AuthMode.OAUTH,
                "",
                "",
                "",
                "",
                "ResalePilot",
                false,
                false
        );
    }
}
