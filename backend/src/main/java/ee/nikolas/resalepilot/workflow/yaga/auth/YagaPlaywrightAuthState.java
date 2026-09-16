package ee.nikolas.resalepilot.workflow.yaga.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YagaPlaywrightAuthState {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private YagaPlaywrightAuthState() {
    }

    public static BrowserContext.StorageStateOptions storageStateOptions(
            Path authStatePath
    ) {
        return new BrowserContext.StorageStateOptions()
                .setIndexedDB(true)
                .setPath(authStatePath);
    }

    public static Browser.NewContextOptions contextOptions(
            Path authStatePath
    ) {
        return new Browser.NewContextOptions()
                .setStorageStatePath(authStatePath)
                .setViewportSize(1440, 900);
    }

    public static void saveSessionStorage(
            Page page,
            Path authStatePath
    ) throws IOException {
        String json = (String) page.evaluate(
                "() => JSON.stringify({" +
                        "origin: window.location.origin," +
                        "sessionStorage: Object.entries(window.sessionStorage)" +
                        ".map(([name, value]) => ({ name, value }))" +
                        "})"
        );
        JsonObject state = new JsonObject();
        JsonArray origins = new JsonArray();
        origins.add(JsonParser.parseString(json).getAsJsonObject());
        state.add("origins", origins);
        Path path = sessionStoragePath(authStatePath);
        Path directory = path.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Files.writeString(path, GSON.toJson(state));
    }

    public static boolean restoreSessionStorage(
            BrowserContext context,
            Path authStatePath
    ) {
        Path path = sessionStoragePath(authStatePath);
        if (Files.notExists(path)) {
            return false;
        }
        try {
            String json = Files.readString(path);
            context.addInitScript(
                    "(() => {" +
                            "const state = " + json + ";" +
                            "const origin = state.origins.find(entry => " +
                            "entry.origin === window.location.origin);" +
                            "if (!origin) return;" +
                            "for (const item of origin.sessionStorage || []) {" +
                            "window.sessionStorage.setItem(item.name, item.value);" +
                            "}" +
                            "})()"
            );
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to restore Yaga sessionStorage state",
                    exception
            );
        }
    }

    public static AuthStateSummary summarize(Path authStatePath) {
        Path resolved = authStatePath.toAbsolutePath().normalize();
        boolean exists = Files.exists(resolved);
        long size = safeSize(resolved);
        Instant modifiedAt = safeModifiedAt(resolved);
        Path sessionStoragePath = sessionStoragePath(resolved);
        boolean sessionStorageConfigured = Files.exists(sessionStoragePath);

        if (!exists) {
            return new AuthStateSummary(
                    resolved,
                    false,
                    size,
                    modifiedAt,
                    0,
                    List.of(),
                    false,
                    List.of(),
                    sessionStorageConfigured,
                    List.of()
            );
        }

        try {
            JsonObject state =
                    JsonParser.parseString(Files.readString(resolved))
                            .getAsJsonObject();
            return new AuthStateSummary(
                    resolved,
                    true,
                    size,
                    modifiedAt,
                    array(state, "cookies").size(),
                    cookieMetadata(array(state, "cookies")),
                    hasIndexedDbEntries(array(state, "origins")),
                    originSummaries(array(state, "origins")),
                    sessionStorageConfigured,
                    sessionStorageKeyNames(sessionStoragePath)
            );
        } catch (IOException exception) {
            return new AuthStateSummary(
                    resolved,
                    true,
                    size,
                    modifiedAt,
                    0,
                    List.of("unreadable-auth-state-json"),
                    false,
                    List.of(),
                    sessionStorageConfigured,
                    List.of()
            );
        }
    }

    public static LiveStorageSummary inspectLivePageStorage(Page page) {
        String json = (String) page.evaluate(
                "() => (async () => {" +
                        "let indexedDbNames = [];" +
                        "try {" +
                        "if (window.indexedDB && window.indexedDB.databases) {" +
                        "indexedDbNames = (await window.indexedDB.databases())" +
                        ".map(db => db.name).filter(Boolean);" +
                        "}" +
                        "} catch (error) {}" +
                        "return JSON.stringify({" +
                        "origin: window.location.origin," +
                        "localStorageKeys: Object.keys(window.localStorage)," +
                        "sessionStorageKeys: Object.keys(window.sessionStorage)," +
                        "indexedDbNames" +
                        "});" +
                        "})()"
        );
        try {
            JsonObject summary =
                    JsonParser.parseString(json).getAsJsonObject();
            return new LiveStorageSummary(
                    string(summary, "origin"),
                    stringArray(summary, "localStorageKeys"),
                    stringArray(summary, "sessionStorageKeys"),
                    stringArray(summary, "indexedDbNames")
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Failed to inspect Yaga storage summary",
                    exception
            );
        }
    }

    public static Path sessionStoragePath(Path authStatePath) {
        Path fileName = authStatePath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException(
                    "Auth state path must include a file name"
            );
        }
        return authStatePath.resolveSibling(
                fileName + ".session-storage.json"
        );
    }

    private static long safeSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException exception) {
            return -1;
        }
    }

    private static Instant safeModifiedAt(Path path) {
        try {
            return Files.exists(path)
                    ? Files.getLastModifiedTime(path).toInstant()
                    : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static List<String> sessionStorageKeyNames(Path path) {
        if (Files.notExists(path)) {
            return List.of();
        }
        try {
            JsonObject state =
                    JsonParser.parseString(Files.readString(path))
                            .getAsJsonObject();
            List<String> keys = new ArrayList<>();
            for (JsonElement originElement : array(state, "origins")) {
                JsonObject origin = originElement.getAsJsonObject();
                for (JsonElement itemElement :
                        array(origin, "sessionStorage")) {
                    JsonObject item = itemElement.getAsJsonObject();
                    keys.add(
                            string(origin, "origin") + ":" +
                                    string(item, "name")
                    );
                }
            }
            Collections.sort(keys);
            return keys;
        } catch (IOException exception) {
            return List.of("unreadable-session-storage-json");
        }
    }

    private static List<String> cookieMetadata(JsonArray cookies) {
        List<String> metadata = new ArrayList<>();
        for (JsonElement element : cookies) {
            JsonObject cookie = element.getAsJsonObject();
            metadata.add(
                    string(cookie, "domain") + " " +
                            string(cookie, "path") + " " +
                            string(cookie, "name") + " " +
                            string(cookie, "sameSite") + " expires=" +
                            numberAsString(cookie, "expires")
            );
        }
        Collections.sort(metadata);
        return metadata;
    }

    private static boolean hasIndexedDbEntries(JsonArray origins) {
        for (JsonElement element : origins) {
            if (!array(element.getAsJsonObject(), "indexedDB").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> originSummaries(JsonArray origins) {
        List<String> summaries = new ArrayList<>();
        for (JsonElement element : origins) {
            JsonObject origin = element.getAsJsonObject();
            summaries.add(
                    string(origin, "origin") +
                            " localStorageKeys=" +
                            storageNames(array(origin, "localStorage")) +
                            " indexedDbDatabases=" +
                            storageNames(array(origin, "indexedDB"))
            );
        }
        Collections.sort(summaries);
        return summaries;
    }

    private static List<String> storageNames(JsonArray items) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : items) {
            names.add(string(element.getAsJsonObject(), "name"));
        }
        Collections.sort(names);
        return names;
    }

    private static List<String> stringArray(
            JsonObject object,
            String memberName
    ) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(object, memberName)) {
            values.add(element.getAsString());
        }
        Collections.sort(values);
        return values;
    }

    private static JsonArray array(JsonObject object, String memberName) {
        JsonElement element = object.get(memberName);
        return element != null && element.isJsonArray()
                ? element.getAsJsonArray()
                : new JsonArray();
    }

    private static String string(JsonObject object, String memberName) {
        JsonElement element = object.get(memberName);
        return element == null || element.isJsonNull()
                ? null
                : element.getAsString();
    }

    private static String numberAsString(
            JsonObject object,
            String memberName
    ) {
        JsonElement element = object.get(memberName);
        return element == null || element.isJsonNull()
                ? "null"
                : element.getAsJsonPrimitive().toString();
    }

    public record AuthStateSummary(
            Path resolvedAuthStatePath,
            boolean fileExists,
            long fileSize,
            Instant fileModifiedAt,
            int savedCookieCount,
            List<String> savedCookieMetadata,
            boolean indexedDbEntriesPresent,
            List<String> savedOrigins,
            boolean sessionStorageConfigured,
            List<String> sessionStorageKeyNames
    ) {
    }

    public record LiveStorageSummary(
            String origin,
            List<String> localStorageKeys,
            List<String> sessionStorageKeys,
            List<String> indexedDbNames
    ) {
        public LiveStorageSummary {
            localStorageKeys = safeList(localStorageKeys);
            sessionStorageKeys = safeList(sessionStorageKeys);
            indexedDbNames = safeList(indexedDbNames);
        }
    }

    private static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }
}
