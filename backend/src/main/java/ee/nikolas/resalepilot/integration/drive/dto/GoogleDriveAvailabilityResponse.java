package ee.nikolas.resalepilot.integration.drive.dto;

import ee.nikolas.resalepilot.integration.drive.config.GoogleDriveProperties;

import java.time.Instant;

public record GoogleDriveAvailabilityResponse(
        boolean available,
        GoogleDriveProperties.AuthMode authMode,
        Instant checkedAt
) {
}
