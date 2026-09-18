package dev.xantha.vss.compat;

/** Refresh a global storage index from player movement, never from queried columns. */
final class LocalIndexRefreshPolicy {
    private int playerX, playerZ, buildX, buildZ;
    private boolean observedPlayer, builtAtPlayer, watchedTeleport;
    private long teleportNanos;

    synchronized void observePlayer(int x, int z, long now) {
        if (observedPlayer && distance(x, z, playerX, playerZ) >= 64) {
            watchedTeleport = true;
            teleportNanos = now;
        }
        playerX = x;
        playerZ = z;
        observedPlayer = true;
    }

    synchronized boolean shouldRefresh(boolean ready, long completedNanos, long now) {
        if (!ready) return true;
        long elapsed = now - completedNanos;
        boolean moved = observedPlayer && (!builtAtPlayer || distance(playerX, playerZ, buildX, buildZ) >= 8);
        boolean teleportFresh = watchedTeleport && now - teleportNanos < 30_000_000_000L;
        return elapsed > 30_000_000_000L
                || moved && elapsed > 5_000_000_000L
                || teleportFresh && elapsed > 500_000_000L;
    }

    synchronized void startedBuild() {
        buildX = playerX;
        buildZ = playerZ;
        builtAtPlayer = observedPlayer;
    }

    private static long distance(int x, int z, int otherX, int otherZ) {
        return Math.max(Math.abs((long) x - otherX), Math.abs((long) z - otherZ));
    }
}
