package ee.nikolas.resalepilot.integration.drive.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAuthException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleDriveServiceTest {

    @Test
    void verifyAvailableUsesOnlyReadOnlyAboutEndpoint()
            throws IOException {

        Drive drive = mock(Drive.class);
        Drive.About about = mock(Drive.About.class);
        Drive.About.Get get = mock(Drive.About.Get.class);

        when(drive.about()).thenReturn(about);
        when(about.get()).thenReturn(get);
        when(get.setFields("user")).thenReturn(get);
        when(get.execute()).thenReturn(new About());

        new GoogleDriveService(drive).verifyAvailable();

        verify(drive).about();
        verify(about).get();
        verify(get).setFields("user");
        verify(get).execute();
        verify(drive, never()).files();
    }

    @Test
    void nestedInvalidGrantIsClassifiedAsAuthenticationFailure()
            throws Exception {

        Drive drive = driveThrowing(new IOException(
                "outer C:\\oauth\\tokens refresh-token drive-file-id",
                tokenResponseException("invalid_grant")
        ));

        ListAppender<ILoggingEvent> appender = attachLogAppender();

        assertThatThrownBy(() -> new GoogleDriveService(drive).verifyAvailable())
                .isInstanceOf(GoogleDriveAuthException.class)
                .hasMessage("Google Drive authentication is invalid or expired")
                .hasMessageNotContaining("refresh-token")
                .hasMessageNotContaining("C:\\oauth\\tokens")
                .hasMessageNotContaining("drive-file-id");

        assertSafeAvailabilityLog(
                appender,
                "java.io.IOException",
                "com.google.api.client.auth.oauth2.TokenResponseException",
                "400",
                "invalid_grant"
        );
    }

    @Test
    void nestedGoogle401IsClassifiedAsAuthenticationFailure()
            throws Exception {

        Drive drive = driveThrowing(new IOException(
                "outer client_secret credential-path",
                googleJsonException(401, "unauthorized")
        ));

        assertThatThrownBy(() -> new GoogleDriveService(drive).verifyAvailable())
                .isInstanceOf(GoogleDriveAuthException.class)
                .hasMessage("Google Drive authentication is invalid or expired")
                .hasMessageNotContaining("client_secret")
                .hasMessageNotContaining("credential-path");
    }

    @Test
    void insufficientPermissionsIsClassifiedAsDriveAccessFailure()
            throws Exception {

        Drive drive = driveThrowing(
                googleJsonException(403, "insufficientPermissions")
        );

        assertThatThrownBy(() -> new GoogleDriveService(drive).verifyAvailable())
                .isInstanceOf(GoogleDriveAccessException.class)
                .hasMessage("Google Drive permission check failed");
    }

    @Test
    void notFoundIsClassifiedAsDriveResourceFailure()
            throws Exception {

        Drive drive = driveThrowing(
                googleJsonException(404, "notFound")
        );

        assertThatThrownBy(() -> new GoogleDriveService(drive).verifyAvailable())
                .isInstanceOf(GoogleDriveAccessException.class)
                .hasMessage("Google Drive resource or configuration was not found");
    }

    @Test
    void timeoutIsClassifiedAsDriveUnavailable()
            throws Exception {

        Drive drive = driveThrowing(
                new SocketTimeoutException(
                        "timeout Authorization Bearer token C:\\secrets"
                )
        );

        ListAppender<ILoggingEvent> appender = attachLogAppender();

        assertThatThrownBy(() -> new GoogleDriveService(drive).verifyAvailable())
                .isInstanceOf(GoogleDriveAccessException.class)
                .hasMessage("Google Drive is currently unavailable")
                .hasMessageNotContaining("Authorization")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("C:\\secrets");

        String logOutput = appender.list.getLast().getFormattedMessage();
        assertThat(logOutput)
                .contains("operationStage=VERIFY_AVAILABLE")
                .contains("safeMessage=Google Drive is currently unavailable")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer")
                .doesNotContain("token")
                .doesNotContain("C:\\secrets");
    }

    private Drive driveThrowing(IOException exception)
            throws IOException {

        Drive drive = mock(Drive.class);
        Drive.About about = mock(Drive.About.class);
        Drive.About.Get get = mock(Drive.About.Get.class);

        when(drive.about()).thenReturn(about);
        when(about.get()).thenReturn(get);
        when(get.setFields("user")).thenReturn(get);
        when(get.execute()).thenThrow(exception);

        return drive;
    }

    private GoogleJsonResponseException googleJsonException(
            int statusCode,
            String reason
    ) {
        GoogleJsonError.ErrorInfo errorInfo =
                new GoogleJsonError.ErrorInfo();
        errorInfo.setReason(reason);

        GoogleJsonError error = new GoogleJsonError();
        error.setCode(statusCode);
        error.setErrors(List.of(errorInfo));

        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(
                        statusCode,
                        "status",
                        new HttpHeaders()
                ),
                error
        );
    }

    private TokenResponseException tokenResponseException(String error)
            throws Exception {

        TokenErrorResponse response = new TokenErrorResponse()
                .setError(error);

        Constructor<TokenResponseException> constructor =
                TokenResponseException.class.getDeclaredConstructor(
                        HttpResponseException.Builder.class,
                        TokenErrorResponse.class
                );
        constructor.setAccessible(true);

        return constructor.newInstance(
                new HttpResponseException.Builder(
                        400,
                        "Bad Request",
                        new HttpHeaders()
                ),
                response
        );
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                GoogleDriveService.class
        );
        logger.setLevel(Level.WARN);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        return appender;
    }

    private void assertSafeAvailabilityLog(
            ListAppender<ILoggingEvent> appender,
            String exceptionClass,
            String rootCauseClass,
            String statusCode,
            String reason
    ) {
        String logOutput = appender.list.getLast().getFormattedMessage();

        assertThat(logOutput)
                .contains("operationStage=VERIFY_AVAILABLE")
                .contains("exceptionClass=" + exceptionClass)
                .contains("rootCauseClass=" + rootCauseClass)
                .contains("googleHttpStatus=" + statusCode)
                .contains("googleReason=" + reason)
                .doesNotContain("refresh-token")
                .doesNotContain("client_secret")
                .doesNotContain("C:\\oauth\\tokens")
                .doesNotContain("drive-file-id");
    }
}
