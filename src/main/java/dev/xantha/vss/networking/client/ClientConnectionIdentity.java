package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.ServerStorageIdentity;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.multiplayer.ServerData;

public final class ClientConnectionIdentity {
    private static final int MAX_ENDPOINT_SUFFIX_LENGTH = 160;
    private static final String STORAGE_MARKER_FILE = ".vss-storage-identity";
    private static final Object DIRECTORY_LOCK = new Object();

    private static volatile SessionIdentity currentSession;
    private static volatile ServerStorageIdentity pendingStorageIdentity;

    private ClientConnectionIdentity() {
    }

    public static void beginSession(ServerData serverData) {
        beginSession(
                serverData == null ? null : serverData.ip,
                serverData != null && serverData.isRealm());
    }

    static synchronized void beginSession(String address, boolean realm) {
        ServerStorageIdentity storageIdentity = pendingStorageIdentity;
        pendingStorageIdentity = null;
        currentSession = new SessionIdentity(
                UUID.randomUUID().toString(),
                normalizeAddress(address),
                realm,
                storageIdentity);
    }

    public static void acceptServerIdentity(ServerIdentityS2CPayload payload) {
        acceptServerIdentity(payload.serverIdentity(), payload.sharedWorld(), payload.nodeIdentity());
    }

    static synchronized void acceptServerIdentity(String serverIdentity, boolean sharedWorld, String nodeIdentity) {
        ServerStorageIdentity storageIdentity = ServerStorageIdentity.create(
                serverIdentity,
                sharedWorld,
                nodeIdentity);
        if (storageIdentity == null) {
            pendingStorageIdentity = null;
            VSSLogger.warn("Ignored invalid VSS server storage identity");
            return;
        }

        SessionIdentity session = currentSession;
        if (session == null) {
            pendingStorageIdentity = storageIdentity;
        } else {
            currentSession = new SessionIdentity(
                    session.sessionId(),
                    session.address(),
                    session.realm(),
                    storageIdentity);
        }
    }

    static void updateSession(ServerData serverData) {
        if (serverData == null) {
            return;
        }
        updateSession(serverData.ip, serverData.isRealm());
    }

    static synchronized void updateSession(String address, boolean realm) {
        String normalizedAddress = normalizeAddress(address);
        if (normalizedAddress == null && !realm) {
            return;
        }
        SessionIdentity session = currentSession;
        String sessionId = session == null ? UUID.randomUUID().toString() : session.sessionId();
        ServerStorageIdentity storageIdentity = session == null
                ? pendingStorageIdentity
                : session.storageIdentity();
        currentSession = new SessionIdentity(sessionId, normalizedAddress, realm, storageIdentity);
    }

    public static synchronized void endSession() {
        currentSession = null;
        pendingStorageIdentity = null;
    }

    static String currentPresenceScope() {
        SessionIdentity session = currentSession;
        if (session == null) {
            return null;
        }
        if (session.realm()) {
            return session.address() == null
                    ? "realm-session:" + session.sessionId()
                    : "realm:" + session.address().toLowerCase(Locale.ROOT);
        }
        if (session.storageIdentity() != null) {
            return "vss:" + session.storageIdentity().cacheKey().toLowerCase(Locale.ROOT);
        }
        return session.address() == null
                ? "server-session:" + session.sessionId()
                : "server:" + session.address().toLowerCase(Locale.ROOT);
    }

    static String ensurePresenceScope() {
        if (currentSession == null) {
            beginSession((String) null, false);
        }
        return currentPresenceScope();
    }

    /** Stable identity for client prediction data; unresolved sessions are not persisted. */
    public static String currentPredictionScope() {
        SessionIdentity session = currentSession;
        if (session == null) return null;
        if (session.storageIdentity() != null) return "vss:" + session.storageIdentity().cacheKey().toLowerCase(Locale.ROOT);
        if (session.address() == null) return null;
        return (session.realm() ? "realm:" : "server:") + session.address().toLowerCase(Locale.ROOT);
    }

    public static Path currentVoxyStoragePath(Path gameDirectory) {
        if (gameDirectory == null) {
            return null;
        }
        SessionIdentity session = currentSession;
        if (session == null) {
            return null;
        }

        Path savesDirectory = gameDirectory.resolve(".voxy").resolve("saves");
        if (session.realm()) {
            return savesDirectory.resolve("realms").toAbsolutePath().normalize();
        }
        if (session.storageIdentity() == null) {
            String endpoint = sanitizeEndpoint(session.address());
            return endpoint == null ? null : savesDirectory.resolve(endpoint).toAbsolutePath().normalize();
        }

        synchronized (DIRECTORY_LOCK) {
            return resolveStableStorageDirectory(savesDirectory, session.storageIdentity(), session.address());
        }
    }

    static String storageDirectoryName(String address, boolean realm) {
        if (realm) {
            return "realms";
        }
        return sanitizeEndpoint(address);
    }

    private static Path resolveStableStorageDirectory(
            Path savesDirectory,
            ServerStorageIdentity storageIdentity,
            String address) {
        String prefix = "vss-" + storageIdentity.cacheKey() + "-";
        String endpoint = sanitizeEndpoint(address);
        Path desired = savesDirectory.resolve(prefix + (endpoint == null ? "UNKNOWN" : endpoint));
        Path selected = desired;

        try {
            if (!Files.isDirectory(desired)) {
                Path previous = findPreviousStorageDirectory(savesDirectory, desired, prefix, storageIdentity.cacheKey());
                if (previous != null) {
                    selected = moveStorageDirectory(previous, desired);
                }
            }
            Files.createDirectories(selected);
            writeStorageMarker(selected, storageIdentity.cacheKey());
        } catch (IOException e) {
            VSSLogger.warn("Failed to prepare Voxy storage folder " + selected.getFileName(), e);
        }

        return selected.toAbsolutePath().normalize();
    }

    private static Path findPreviousStorageDirectory(
            Path savesDirectory,
            Path desired,
            String prefix,
            String storageKey) throws IOException {
        if (!Files.isDirectory(savesDirectory)) {
            return null;
        }

        List<Path> prefixed = new ArrayList<>();
        List<Path> marked = new ArrayList<>();
        try (var paths = Files.list(savesDirectory)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(desired))
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(path -> {
                        prefixed.add(path);
                        if (hasStorageMarker(path, storageKey)) {
                            marked.add(path);
                        }
                    });
        }

        List<Path> candidates = marked.isEmpty() && prefixed.size() == 1 ? prefixed : marked;
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingLong(ClientConnectionIdentity::lastModifiedMillis).reversed());
        if (candidates.size() > 1) {
            VSSLogger.warn("Found multiple Voxy folders for storage identity " + storageKey
                    + "; reusing the most recently modified one");
        }
        return candidates.get(0);
    }

    private static Path moveStorageDirectory(Path previous, Path desired) {
        try {
            Files.move(previous, desired, StandardCopyOption.ATOMIC_MOVE);
            VSSLogger.info("Updated multiplayer Voxy storage folder: "
                    + previous.getFileName() + " -> " + desired.getFileName());
            return desired;
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                Files.move(previous, desired);
                VSSLogger.info("Updated multiplayer Voxy storage folder: "
                        + previous.getFileName() + " -> " + desired.getFileName());
                return desired;
            } catch (IOException e) {
                VSSLogger.warn("Could not rename multiplayer Voxy storage folder; reusing "
                        + previous.getFileName(), e);
                return previous;
            }
        } catch (IOException e) {
            VSSLogger.warn("Could not rename multiplayer Voxy storage folder; reusing "
                    + previous.getFileName(), e);
            return previous;
        }
    }

    private static boolean hasStorageMarker(Path directory, String storageKey) {
        try {
            Path marker = directory.resolve(STORAGE_MARKER_FILE);
            return Files.isRegularFile(marker) && storageKey.equals(Files.readString(marker).trim());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void writeStorageMarker(Path directory, String storageKey) throws IOException {
        Path marker = directory.resolve(STORAGE_MARKER_FILE);
        if (!hasStorageMarker(directory, storageKey)) {
            Files.writeString(marker, storageKey);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static String sanitizeEndpoint(String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replace("[", "").replace("]", "");
        StringBuilder safe = new StringBuilder(Math.min(normalized.length(), MAX_ENDPOINT_SUFFIX_LENGTH));
        for (int index = 0; index < normalized.length() && safe.length() < MAX_ENDPOINT_SUFFIX_LENGTH; index++) {
            char character = normalized.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.'
                    || character == '-'
                    || character == '_') {
                safe.append(character);
            } else {
                safe.append('_');
            }
        }
        return safe.isEmpty() ? null : safe.toString();
    }

    private static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String normalized = address.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record SessionIdentity(
            String sessionId,
            String address,
            boolean realm,
            ServerStorageIdentity storageIdentity) {
    }
}
