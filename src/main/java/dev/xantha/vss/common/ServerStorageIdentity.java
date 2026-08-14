package dev.xantha.vss.common;

import java.security.SecureRandom;
import java.util.Locale;

public record ServerStorageIdentity(String serverIdentity, boolean sharedWorld, String nodeIdentity) {
    public static final int SERVER_IDENTITY_LENGTH = 8;
    public static final int MAX_NODE_IDENTITY_LENGTH = 32;

    private static final String IDENTITY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom IDENTITY_RANDOM = new SecureRandom();

    public static ServerStorageIdentity create(
            String serverIdentity,
            boolean sharedWorld,
            String nodeIdentity) {
        String normalizedServerIdentity = normalizeServerIdentity(serverIdentity);
        if (normalizedServerIdentity == null) {
            return null;
        }
        String normalizedNodeIdentity = normalizeNodeIdentity(nodeIdentity);
        if (!sharedWorld && normalizedNodeIdentity.isEmpty()) {
            return null;
        }
        return new ServerStorageIdentity(normalizedServerIdentity, sharedWorld, normalizedNodeIdentity);
    }

    public static String generateIdentity() {
        StringBuilder identity = new StringBuilder(SERVER_IDENTITY_LENGTH);
        for (int index = 0; index < SERVER_IDENTITY_LENGTH; index++) {
            identity.append(IDENTITY_ALPHABET.charAt(IDENTITY_RANDOM.nextInt(IDENTITY_ALPHABET.length())));
        }
        return identity.toString();
    }

    public static String normalizeServerIdentity(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != SERVER_IDENTITY_LENGTH) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (IDENTITY_ALPHABET.indexOf(normalized.charAt(index)) < 0) {
                return null;
            }
        }
        return normalized;
    }

    public static String normalizeNodeIdentity(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(Math.min(normalized.length(), MAX_NODE_IDENTITY_LENGTH));
        for (int index = 0; index < normalized.length() && safe.length() < MAX_NODE_IDENTITY_LENGTH; index++) {
            char character = normalized.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '_') {
                safe.append(character);
            } else {
                safe.append('-');
            }
        }
        return safe.toString();
    }

    public String cacheKey() {
        if (sharedWorld) {
            return serverIdentity;
        }
        String nodeStorageToken = nodeIdentity.startsWith("node-") ? nodeIdentity : "node-" + nodeIdentity;
        return serverIdentity + "-" + nodeStorageToken;
    }
}
