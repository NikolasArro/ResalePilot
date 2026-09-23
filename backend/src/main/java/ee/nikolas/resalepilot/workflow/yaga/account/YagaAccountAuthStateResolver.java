package ee.nikolas.resalepilot.workflow.yaga.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YagaAccountAuthStateResolver {

    public static final long LEGACY_DEFAULT_ACCOUNT_ID = 1L;
    public static final Path DIAGNOSTIC_AUTH_DIRECTORY =
            Paths.get("playwright", ".auth");
    public static final Path DIAGNOSTIC_LEGACY_AUTH_STATE_PATH =
            DIAGNOSTIC_AUTH_DIRECTORY.resolve("yaga-state.json");

    private YagaAccountAuthStateResolver() {
    }

    public static Path resolve(
            YagaAccount account,
            String fallbackAuthStatePath
    ) {
        return resolvePath(
                authStatePathFor(account, fallbackAuthStatePath)
        );
    }

    public static Path resolveDiagnostic(
            Long accountId,
            Path explicitAuthStatePath
    ) {
        if (explicitAuthStatePath != null) {
            return explicitAuthStatePath.toAbsolutePath().normalize();
        }
        return resolvePath(
                defaultAuthStatePathFor(
                        accountId,
                        DIAGNOSTIC_LEGACY_AUTH_STATE_PATH.toString()
                )
        );
    }

    private static String authStatePathFor(
            YagaAccount account,
            String fallbackAuthStatePath
    ) {
        if (account != null &&
                account.getAuthStatePath() != null &&
                !account.getAuthStatePath().isBlank()) {
            return account.getAuthStatePath();
        }
        Long accountId = account == null ? null : account.getId();
        return defaultAuthStatePathFor(accountId, fallbackAuthStatePath);
    }

    private static String defaultAuthStatePathFor(
            Long accountId,
            String fallbackAuthStatePath
    ) {
        if (accountId == null ||
                accountId == LEGACY_DEFAULT_ACCOUNT_ID) {
            return fallbackAuthStatePath;
        }

        Path fallbackPath = Paths.get(fallbackAuthStatePath);
        Path parent = fallbackPath.getParent();
        Path authDirectory = parent == null
                ? Paths.get(".")
                : parent;
        return authDirectory
                .resolve("yaga-account-" + accountId + "-state.json")
                .toString();
    }

    private static Path resolvePath(String path) {
        Path resolved = Paths.get(path).toAbsolutePath().normalize();
        if (Paths.get(path).isAbsolute() || Files.exists(resolved)) {
            return resolved;
        }

        String normalizedPath = path.replace('\\', '/');
        if (normalizedPath.startsWith("../playwright/")) {
            Path repositoryRootCandidate = Paths
                    .get(normalizedPath.substring(3))
                    .toAbsolutePath()
                    .normalize();
            if (Files.exists(repositoryRootCandidate)) {
                return repositoryRootCandidate;
            }
        }

        return resolved;
    }
}
