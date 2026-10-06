package dev.xantha.vss.client.prediction;

import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Session-local, bounded city planning hints for fine prediction tiles. */
final class LostCityHints {
    private static final int MAX_REGIONS = 512;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final ResourceKey<Level> dimension;
    private final long session;
    private final boolean installed;
    private final Map<Long, List<LostCityPreview.Chunk>> regions = new ConcurrentHashMap<>();
    private final Set<Long> wanted = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> requested = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    private final java.util.function.Consumer<LostCityHintsC2SPayload> send;
    private long unavailableUntil;
    private long lastSent;
    private long lastPrune;
    private long retainedBytes;
    private int playerChunkX, playerChunkZ;

    LostCityHints(ResourceKey<Level> dimension, long session) {
        this(dimension, session, ModList.get() != null && ModList.get().isLoaded("lostcities"));
    }

    LostCityHints(ResourceKey<Level> dimension, long session, boolean installed) {
        this(dimension, session, installed, System::nanoTime, VSSNetworking::sendToServer);
    }

    LostCityHints(ResourceKey<Level> dimension, long session, boolean installed,
                  java.util.function.LongSupplier clock, java.util.function.Consumer<LostCityHintsC2SPayload> send) {
        this.clock = clock;
        this.send = send;
        this.dimension = dimension;
        this.session = session;
        this.installed = installed;
    }

    void observeArea(int baseX, int baseZ, int span) {
        if (!installed) return;
        for (int z = Math.floorDiv(baseZ, 128); z <= Math.floorDiv(baseZ + span - 1, 128); z++)
            for (int x = Math.floorDiv(baseX, 128); x <= Math.floorDiv(baseX + span - 1, 128); x++) {
                long key = key(x, z);
                if (!regions.containsKey(key)) wanted.add(key);
            }
    }

    void tick(int playerChunkX, int playerChunkZ) {
        if (!installed) return;
        this.playerChunkX = playerChunkX;
        this.playerChunkZ = playerChunkZ;
        long now = clock.getAsLong();
        if (now - lastPrune >= 5_000_000_000L) {
            for (long key : regions.keySet()) {
                if (Math.abs((long) (int) (key >> 32) * 8 - playerChunkX) > 288
                        || Math.abs((long) (int) key * 8 - playerChunkZ) > 288) removeRegion(key, now);
            }
            requested.entrySet().removeIf(entry -> entry.getValue() < now && !wanted.contains(entry.getKey()));
            lastPrune = now;
        }
        if (wanted.isEmpty() || now - unavailableUntil < 0) return;
        if (now - lastSent < 200_000_000L) return;
        long best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (long key : wanted) {
            if (regions.containsKey(key)) { wanted.remove(key); continue; }
            long dx = (long) (int) (key >> 32) * 8 + 4 - playerChunkX;
            long dz = (long) (int) key * 8 + 4 - playerChunkZ;
            long distance = dx * dx + dz * dz;
            if (distance > 256L * 256L) { wanted.remove(key); requested.remove(key); continue; }
            if (now - requested.getOrDefault(key, 0L) < 5_000_000_000L) continue;
            if (distance < bestDistance) { best = key; bestDistance = distance; }
        }
        if (bestDistance == Long.MAX_VALUE) return;
        lastSent = now;
        requested.put(best, now);
        send.accept(new LostCityHintsC2SPayload(dimension.location(),
                (int) (best >> 32), (int) best, session));
    }

    boolean accept(LostCityHintsS2CPayload response) {
        if (!installed || !dimension.location().equals(response.dimension()) || session != response.session()) return false;
        long key = key(response.regionX(), response.regionZ());
        requested.remove(key);
        if (!response.active()) {
            // Unavailable is not a successful empty-city result. Keep any last
            // good geometry, and back off the whole capability to avoid probing
            // every region when the installed planner is temporarily unavailable.
            if (!regions.containsKey(key)) wanted.add(key);
            unavailableUntil = clock.getAsLong() + 30_000_000_000L;
            return false;
        }
        wanted.remove(key);
        List<LostCityPreview.Chunk> value = response.chunks();
        List<LostCityPreview.Chunk> old = regions.put(key, value);
        retainedBytes += LostCityPreview.retainedBytes(value) - (old == null ? 0 : LostCityPreview.retainedBytes(old));
        while (regions.size() > MAX_REGIONS || retainedBytes > MAX_BYTES) {
            long farthest = key, distance = -1;
            for (long candidate : regions.keySet()) {
                long dx = (long) (int) (candidate >> 32) * 8 + 4 - playerChunkX;
                long dz = (long) (int) candidate * 8 + 4 - playerChunkZ;
                long next = Math.max(Math.abs(dx), Math.abs(dz));
                if (next > distance) { farthest = candidate; distance = next; }
            }
            removeRegion(farthest, clock.getAsLong());
        }
        var retained = regions.get(key);
        return !java.util.Objects.equals(old, retained) && (containsCity(old) || containsCity(retained));
    }

    private void removeRegion(long key, long now) {
        var removed = regions.remove(key);
        if (removed != null) retainedBytes -= LostCityPreview.retainedBytes(removed);
        // Repeated tile builds must not immediately re-request an evicted far
        // region while the nearby summaries still consume the cache budget.
        requested.put(key, now + 25_000_000_000L);
    }

    long retainedBytes() { return retainedBytes; }

    private static boolean containsCity(List<LostCityPreview.Chunk> values) {
        if (values == null) return false;
        for (var value : values) if (value.kind() != 0 || !value.overlays().isEmpty()) return true;
        return false;
    }

    LostCityPreview.Chunk chunk(int chunkX, int chunkZ) {
        var region = regions.get(key(Math.floorDiv(chunkX, 8), Math.floorDiv(chunkZ, 8)));
        return region == null || region.isEmpty() ? LostCityPreview.EMPTY
                : region.get(Math.floorMod(chunkZ, 8) * 8 + Math.floorMod(chunkX, 8));
    }

    boolean regionKnown(int x, int z) { return regions.containsKey(key(x, z)); }

    record Snapshot(LostCityPreview.Tile tile, boolean complete) {
        LostCityPreview.Tile buildings() { return PredictionCityMeshCache.buildings(tile); }
    }

    LostCityPreview.Tile snapshot(int baseX, int baseZ, int span) {
        return cacheSnapshot(baseX, baseZ, span).buildings();
    }

    boolean agrees(LostCityPreview.Tile stored, int baseX, int baseZ, int span) {
        if (!installed || stored.minChunkX() != Math.floorDiv(baseX, 16) - 1
                || stored.minChunkZ() != Math.floorDiv(baseZ, 16) - 1
                || stored.side() != Math.floorDiv(span + 15, 16) + 2) return false;
        for (int z = 0; z < stored.side(); z++) for (int x = 0; x < stored.side(); x++) {
            int cx = stored.minChunkX() + x, cz = stored.minChunkZ() + z;
            var region = regions.get(key(Math.floorDiv(cx, 8), Math.floorDiv(cz, 8)));
            if (region != null && !region.get(Math.floorMod(cz, 8) * 8 + Math.floorMod(cx, 8))
                    .equals(stored.chunks().get(z * stored.side() + x))) return false;
        }
        return true;
    }

    Snapshot cacheSnapshot(int baseX, int baseZ, int span) {
        if (!installed) return new Snapshot(null, true);
        // A one-chunk gutter keeps ground summaries and seam walls consistent across tile borders.
        int baseChunkX = Math.floorDiv(baseX, 16) - 1, baseChunkZ = Math.floorDiv(baseZ, 16) - 1;
        int side = Math.floorDiv(span + 15, 16) + 2;
        var result = new java.util.ArrayList<LostCityPreview.Chunk>(side * side);
        boolean complete = true;
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            int cx = baseChunkX + x, cz = baseChunkZ + z;
            var region = regions.get(key(Math.floorDiv(cx, 8), Math.floorDiv(cz, 8)));
            complete &= region != null;
            result.add(region == null ? LostCityPreview.EMPTY
                    : region.get(Math.floorMod(cz, 8) * 8 + Math.floorMod(cx, 8)));
        }
        return new Snapshot(new LostCityPreview.Tile(baseChunkX, baseChunkZ, side, result), complete);
    }

    static PredictionDepthBound includeBuildings(PredictionDepthBound bounds, LostCityPreview.Tile buildings) {
        if (buildings == null) return bounds;
        int min = bounds.minY(), max = bounds.maxY();
        for (var hint : buildings.chunks()) {
            if (hint.kind() != 0) {
                min = Math.min(min, hint.ground());
                max = Math.max(max, hint.top());
            }
            for (var overlay : hint.overlays()) {
                min = Math.min(min, overlay.y());
                max = Math.max(max, overlay.y() + Math.max(overlay.model().height(), overlay.distant().height()));
                if (overlay.water() != null) max = Math.max(max, overlay.y()
                        + Math.max(overlay.water().height(), overlay.waterDistant().height()));
            }
        }
        return new PredictionDepthBound(min, max);
    }

    /** Legacy packed-hint accessors retained for old replay fixtures and API tests. */
    static int kind(int packed) { return packed & 3; }
    static int ground(int packed) { return (short) (packed >>> 2); }
    static int floors(int packed) { return packed >>> 18 & 127; }
    static int style(int packed) { return packed >>> 25 & 15; }
    static int top(int packed) { return ground(packed) + Math.min(20, floors(packed)) * 6; }

    static PredictionDepthBound includeBuildings(PredictionDepthBound bounds, int[] buildings) {
        if (buildings == null) return bounds;
        int min = bounds.minY(), max = bounds.maxY();
        for (int hint : buildings) if (kind(hint) == LostCityPreview.BUILDING) {
            min = Math.min(min, ground(hint));
            max = Math.max(max, top(hint));
        }
        return new PredictionDepthBound(min, max);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
}
