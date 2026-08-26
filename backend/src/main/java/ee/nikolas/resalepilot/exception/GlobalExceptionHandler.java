package ee.nikolas.resalepilot.exception;

import ee.nikolas.resalepilot.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductNotFound(
            ProductNotFoundException exception
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateSku(
            DuplicateSkuException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> validationErrors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        validationErrors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                validationErrors
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            Map<String, String> validationErrors
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                validationErrors
        );

        return ResponseEntity
                .status(status)
                .body(response);
    }

    @ExceptionHandler(InvalidProductStatusTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidStatusTransition(
            InvalidProductStatusTransitionException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(DuplicateProductImageException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateProductImage(
            DuplicateProductImageException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(ProductImageNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductImageNotFound(
            ProductImageNotFoundException exception
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(InvalidProductImageOrderException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidImageOrder(
            InvalidProductImageOrderException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(GoogleDriveFileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDriveFileNotFound(
            GoogleDriveFileNotFoundException exception
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(GoogleDriveAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDriveAccess(
            GoogleDriveAccessException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage(),
                Map.of()
        );
    }
}