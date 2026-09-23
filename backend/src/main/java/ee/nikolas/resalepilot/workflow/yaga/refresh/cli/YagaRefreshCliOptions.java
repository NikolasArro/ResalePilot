package ee.nikolas.resalepilot.workflow.yaga.refresh.cli;

import ee.nikolas.resalepilot.workflow.yaga.refresh.exception.YagaRefreshRequestInvalidException;

import java.util.UUID;

public record YagaRefreshCliOptions(
        Long accountId,
        int count,
        String cdpUrl,
        String idempotencyKey
) {
    private static final String DEFAULT_CDP_URL =
            "http://127.0.0.1:9333";

    static YagaRefreshCliOptions parse(String[] args) {
        Long accountId = null;
        Integer count = null;
        String cdpUrl = DEFAULT_CDP_URL;
        String idempotencyKey = null;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--account-id".equals(arg) && index + 1 < args.length) {
                accountId = Long.parseLong(args[++index]);
                continue;
            }
            if (("--count".equals(arg) || "--batch-size".equals(arg)) &&
                    index + 1 < args.length) {
                count = Integer.parseInt(args[++index]);
                continue;
            }
            if ("--cdp-url".equals(arg) && index + 1 < args.length) {
                cdpUrl = args[++index];
                continue;
            }
            if ("--idempotency-key".equals(arg) && index + 1 < args.length) {
                idempotencyKey = args[++index];
            }
        }

        if (accountId == null) {
            throw new YagaRefreshRequestInvalidException(
                    "--account-id is required"
            );
        }
        if (count == null || count < 1) {
            throw new YagaRefreshRequestInvalidException(
                    "--count must be at least 1"
            );
        }

        return new YagaRefreshCliOptions(
                accountId,
                count,
                cdpUrl,
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? "on-demand-" + UUID.randomUUID()
                        : idempotencyKey
        );
    }
}
