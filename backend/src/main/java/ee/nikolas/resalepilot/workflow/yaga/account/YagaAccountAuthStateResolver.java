package ee.nikolas.resalepilot.workflow.yaga.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YagaAccountAuthStateResolver {

    private YagaAccountAuthStateResolver() {
    }

    public static Path resolve(
            YagaAccount account,
            String fallbackAuthStatePath
    ) {
        String path = account != null && account.getAuthStatePath() != null &&
                !account.getAuthStatePath().isBlank()
                ? account.getAuthStatePath()
                : fallbackAuthStatePath;

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
