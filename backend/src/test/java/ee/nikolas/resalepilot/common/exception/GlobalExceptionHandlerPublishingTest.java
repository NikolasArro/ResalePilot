package ee.nikolas.resalepilot.common.exception;

import ee.nikolas.resalepilot.common.dto.ApiErrorResponse;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormDiagnostics;
import ee.nikolas.resalepilot.workflow.yaga.publishing.exception.YagaPublishingFormException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerPublishingTest {

    @Test
    void publishingFailureReturnsOnlySafeStageDiagnostics() {
        RuntimeException cause = new RuntimeException(
                "cookie=secret Authorization=secret auth-state-path"
        );
        YagaPublishingFormDiagnostics diagnostics =
                YagaPublishingFormDiagnostics.failureMetadata(
                        "VERIFY_CONDITION",
                        cause.getClass().getName(),
                        cause.getClass().getName(),
                        "CONDITION_SELECTION_NOT_CONFIRMED"
                );
        YagaPublishingFormException exception =
                new YagaPublishingFormException(
                        "Yaga condition selection was not confirmed",
                        diagnostics,
                        cause
                );

        ResponseEntity<ApiErrorResponse> response =
                new GlobalExceptionHandler()
                        .handleYagaPublishingForm(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().validationErrors())
                .containsEntry("operationStage", "VERIFY_CONDITION")
                .containsEntry(
                        "safeErrorCode",
                        "CONDITION_SELECTION_NOT_CONFIRMED"
                )
                .containsEntry(
                        "exceptionClass",
                        RuntimeException.class.getName()
                );
        assertThat(response.getBody().toString())
                .doesNotContain("cookie", "Authorization", "auth-state");
    }
}
