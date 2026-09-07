package ee.nikolas.resalepilot.integration.drive.service;

import ee.nikolas.resalepilot.integration.drive.model.DownloadedDriveFile;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import ee.nikolas.resalepilot.integration.drive.dto.GoogleDriveFileResponse;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAccessException;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveAuthException;
import ee.nikolas.resalepilot.integration.drive.exception.GoogleDriveFileNotFoundException;
import ee.nikolas.resalepilot.integration.drive.exception.InvalidGoogleDriveFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
@ConditionalOnProperty(
        name = "google.drive.enabled",
        havingValue = "true"
)
public class GoogleDriveService {

    private static final Logger log =
            LoggerFactory.getLogger(GoogleDriveService.class);

    private static final String VERIFY_AVAILABLE_STAGE =
            "VERIFY_AVAILABLE";

    private static final Set<String> AUTH_ERROR_REASONS = Set.of(
            "invalid_grant",
            "invalid_token",
            "unauthorized",
            "unauthorized_client"
    );

    private static final Set<String> ACCESS_ERROR_REASONS = Set.of(
            "insufficientpermissions",
            "insufficient_permissions"
    );

    private final Drive drive;

    public GoogleDriveService(Drive drive) {
        this.drive = drive;
    }

    public void verifyAvailable() {
        try {
            drive.about()
                    .get()
                    .setFields("user")
                    .execute();

        } catch (IOException exception) {
            throwAvailabilityException(exception);
        } catch (RuntimeException exception) {
            throwAvailabilityException(exception);
        }
    }

    private void throwAvailabilityException(Exception exception) {
        DriveAvailabilityFailure failure =
                classifyAvailabilityFailure(exception);

        log.warn(
                "Google Drive availability check failed: "
                        + "operationStage={}, exceptionClass={}, "
                        + "rootCauseClass={}, googleHttpStatus={}, "
                        + "googleReason={}, safeMessage={}",
                VERIFY_AVAILABLE_STAGE,
                exception.getClass().getName(),
                failure.rootCauseClass(),
                failure.googleHttpStatus(),
                failure.googleReason(),
                failure.safeMessage()
        );

        if (failure.authFailure()) {
            throw new GoogleDriveAuthException(
                    failure.safeMessage(),
                    exception
            );
        }

        throw new GoogleDriveAccessException(
                failure.safeMessage(),
                exception
        );
    }

    private DriveAvailabilityFailure classifyAvailabilityFailure(
            Exception exception
    ) {
        Throwable rootCause = exception;
        Integer googleHttpStatus = null;
        String googleReason = null;

        for (Throwable current = exception;
             current != null;
             current = current.getCause()) {

            rootCause = current;

            if (current instanceof GoogleJsonResponseException googleException) {
                googleHttpStatus = googleException.getStatusCode();
                googleReason = firstNonBlank(
                        googleReason,
                        getGoogleJsonReason(googleException)
                );
            }

            if (current instanceof TokenResponseException tokenException) {
                googleHttpStatus = firstNonNull(
                        googleHttpStatus,
                        tokenException.getStatusCode()
                );
                googleReason = firstNonBlank(
                        googleReason,
                        getTokenReason(tokenException)
                );
            }
        }

        String safeGoogleReason =
                sanitizeGoogleReason(googleReason);

        String normalizedReason =
                normalizeReason(safeGoogleReason);

        if (isAuthFailure(googleHttpStatus, normalizedReason)) {
            return new DriveAvailabilityFailure(
                    true,
                    "Google Drive authentication is invalid or expired",
                    googleHttpStatus,
                    safeGoogleReason,
                    rootCause.getClass().getName()
            );
        }

        if (googleHttpStatus != null && googleHttpStatus == 403
                || ACCESS_ERROR_REASONS.contains(normalizedReason)) {

            return new DriveAvailabilityFailure(
                    false,
                    "Google Drive permission check failed",
                    googleHttpStatus,
                    safeGoogleReason,
                    rootCause.getClass().getName()
            );
        }

        if (googleHttpStatus != null && googleHttpStatus == 404
                || "notfound".equals(normalizedReason)) {

            return new DriveAvailabilityFailure(
                    false,
                    "Google Drive resource or configuration was not found",
                    googleHttpStatus,
                    safeGoogleReason,
                    rootCause.getClass().getName()
            );
        }

        if (isUnavailableFailure(exception, googleHttpStatus)) {
            return new DriveAvailabilityFailure(
                    false,
                    "Google Drive is currently unavailable",
                    googleHttpStatus,
                    safeGoogleReason,
                    rootCause.getClass().getName()
            );
        }

        return new DriveAvailabilityFailure(
                false,
                "Google Drive availability check failed",
                googleHttpStatus,
                safeGoogleReason,
                rootCause.getClass().getName()
        );
    }

    private boolean isAuthFailure(
            Integer googleHttpStatus,
            String normalizedReason
    ) {
        return googleHttpStatus != null && googleHttpStatus == 401
                || AUTH_ERROR_REASONS.contains(normalizedReason)
                || normalizedReason.contains("revoked")
                || normalizedReason.contains("expired");
    }

    private boolean isUnavailableFailure(
            Exception exception,
            Integer googleHttpStatus
    ) {
        if (googleHttpStatus != null && googleHttpStatus >= 500) {
            return true;
        }

        for (Throwable current = exception;
             current != null;
             current = current.getCause()) {

            if (current instanceof SocketTimeoutException) {
                return true;
            }
        }

        return exception instanceof IOException;
    }

    private String getGoogleJsonReason(
            GoogleJsonResponseException exception
    ) {
        GoogleJsonError details = exception.getDetails();

        if (details == null || details.getErrors() == null) {
            return null;
        }

        return details.getErrors()
                .stream()
                .map(GoogleJsonError.ErrorInfo::getReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String getTokenReason(
            TokenResponseException exception
    ) {
        if (exception.getDetails() == null) {
            return null;
        }

        return exception.getDetails().getError();
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            return "";
        }

        return reason
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    private String sanitizeGoogleReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }

        String trimmed = reason.trim();
        if (trimmed.length() > 80) {
            return "unknown";
        }

        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            boolean safe =
                    character >= 'a' && character <= 'z'
                            || character >= 'A' && character <= 'Z'
                            || character >= '0' && character <= '9'
                            || character == '_'
                            || character == '-'
                            || character == '.';

            if (!safe) {
                return "unknown";
            }
        }

        return trimmed;
    }

    private String firstNonBlank(
            String current,
            String candidate
    ) {
        if (current != null && !current.isBlank()) {
            return current;
        }

        return candidate;
    }

    private Integer firstNonNull(
            Integer current,
            Integer candidate
    ) {
        return current == null ? candidate : current;
    }

    private record DriveAvailabilityFailure(
            boolean authFailure,
            String safeMessage,
            Integer googleHttpStatus,
            String googleReason,
            String rootCauseClass
    ) {
    }

    public GoogleDriveFileResponse getFileMetadata(String fileId) {
        try {
            File file = drive.files()
                    .get(fileId)
                    .setFields(
                            "id,name,mimeType,size,modifiedTime"
                    )
                    .execute();

            String modifiedTime =
                    file.getModifiedTime() == null
                            ? null
                            : file.getModifiedTime().toStringRfc3339();

            return new GoogleDriveFileResponse(
                    file.getId(),
                    file.getName(),
                    file.getMimeType(),
                    file.getSize(),
                    modifiedTime
            );

        } catch (GoogleJsonResponseException exception) {
            if (exception.getStatusCode() == 404) {
                throw new GoogleDriveFileNotFoundException(fileId);
            }

            throw new GoogleDriveAccessException(
                    "Google Drive rejected access to file: " + fileId,
                    exception
            );

        } catch (IOException exception) {
            throw new GoogleDriveAccessException(
                    "Failed to read Google Drive file: " + fileId,
                    exception
            );
        }
    }

    public DownloadedDriveFile downloadToTemporaryFile(
            String fileId
    ) {
        GoogleDriveFileResponse metadata =
                getFileMetadata(fileId);

        String suffix = getImageSuffix(
                fileId,
                metadata.mimeType()
        );

        Path temporaryFile = null;

        try {
            temporaryFile = Files.createTempFile(
                    "resalepilot-",
                    suffix
            );

            try (OutputStream outputStream =
                         Files.newOutputStream(temporaryFile)) {

                drive.files()
                        .get(fileId)
                        .executeMediaAndDownloadTo(outputStream);
            }

            return new DownloadedDriveFile(
                    fileId,
                    metadata.name(),
                    metadata.mimeType(),
                    temporaryFile
            );

        } catch (IOException exception) {
            deletePartialFile(temporaryFile);

            throw new GoogleDriveAccessException(
                    "Failed to download Google Drive file: " + fileId,
                    exception
            );
        }
    }

    private String getImageSuffix(
            String fileId,
            String mimeType
    ) {
        if (mimeType == null) {
            throw new InvalidGoogleDriveFileException(
                    fileId,
                    null
            );
        }

        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default ->
                    throw new InvalidGoogleDriveFileException(
                            fileId,
                            mimeType
                    );
        };
    }

    private void deletePartialFile(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Не заменяем исходную ошибку ошибкой очистки.
        }
    }
}
