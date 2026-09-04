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

    @ExceptionHandler(MarketplaceListingNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMarketplaceListingNotFound(
            MarketplaceListingNotFoundException exception
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

    @ExceptionHandler(InvalidGoogleDriveFileException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDriveFile(
            InvalidGoogleDriveFileException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaImportException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaImport(
            YagaImportException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaImageDownloadException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaImageDownload(
            YagaImageDownloadException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaImportConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaImportConflict(
            YagaImportConflictException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublishingDataInvalidException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublishingDataInvalid(
            YagaPublishingDataInvalidException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublishingAuthException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublishingAuth(
            YagaPublishingAuthException exception
    ) {
        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaHidingAuthException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaHidingAuth(
            YagaHidingAuthException exception
    ) {
        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                exception.getMessage(),
                exception.getDetails()
        );
    }

    @ExceptionHandler(YagaPreparationAlreadyRunningException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPreparationConflict(
            YagaPreparationAlreadyRunningException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublishingFormException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublishingForm(
            YagaPublishingFormException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublishingDriveDownloadException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublishingDriveDownload(
            YagaPublishingDriveDownloadException exception
    ) {
        return buildResponse(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublicationPreparationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublicationNotFound(
            YagaPublicationPreparationNotFoundException exception
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler({
            YagaPublicationForbiddenException.class,
            YagaPublicationConfirmDisabledException.class
    })
    public ResponseEntity<ApiErrorResponse> handleYagaPublicationForbidden(
            RuntimeException exception
    ) {
        return buildResponse(
                HttpStatus.FORBIDDEN,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublicationExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublicationExpired(
            YagaPublicationExpiredException exception
    ) {
        return buildResponse(
                HttpStatus.GONE,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublicationInvalidStateException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaPublicationInvalidState(
            YagaPublicationInvalidStateException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                Map.of()
        );
    }

    @ExceptionHandler(YagaPublicationReconciliationConflictException.class)
    public ResponseEntity<ApiErrorResponse>
    handleYagaPublicationReconciliationConflict(
            YagaPublicationReconciliationConflictException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                exception.getDetails()
        );
    }

    @ExceptionHandler(YagaHidingPreconditionException.class)
    public ResponseEntity<ApiErrorResponse> handleYagaHidingPrecondition(
            YagaHidingPreconditionException exception
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                exception.getDetails()
        );
    }
}
