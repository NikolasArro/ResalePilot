package ee.nikolas.resalepilot.workflow.yaga.account;

import java.time.Instant;

public record YagaAccountResponse(
        Long id,
        String name,
        String shopSlug,
        String authStatePath,
        String driveFolderId,
        boolean enabled,
        boolean autoRefreshEnabled,
        int batchSize,
        Instant createdAt,
        Instant updatedAt
) {
    static YagaAccountResponse from(YagaAccount account) {
        return new YagaAccountResponse(
                account.getId(),
                account.getName(),
                account.getShopSlug(),
                account.getAuthStatePath(),
                account.getDriveFolderId(),
                account.isEnabled(),
                account.isAutoRefreshEnabled(),
                account.getBatchSize(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
