package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;

/** Self-contained city inputs for a finished mesh; never a replacement for server hints. */
final class PredictionCityMeshCache {
    private static final int MAX_SIDE = 66;

    static byte[] encode(LostCityPreview.Tile tile) throws IOException {
        if (tile.side() > MAX_SIDE) throw new IOException("city cache extent");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(tile.minChunkX()); out.writeInt(tile.minChunkZ()); out.writeInt(tile.side());
            var unique = new LinkedHashMap<LostCityPreview.Chunk, Integer>();
            for (var chunk : tile.chunks()) unique.computeIfAbsent(chunk, ignored -> unique.size());
            out.writeInt(unique.size());
            // Synced state numbers may change with the mod registry. Validate every used
            // state's name/properties on read, including models not present in terrain.
            var states = new TreeSet<Integer>();
            for (var chunk : unique.keySet()) {
                states.add(chunk.surfaceState()); states.add(chunk.pavementState());
                collect(states, chunk.silhouette());
                for (var floor : chunk.floors()) collect(states, floor.model());
                for (var overlay : chunk.overlays()) {
                    collect(states, overlay.model()); collect(states, overlay.distant());
                    collect(states, overlay.water()); collect(states, overlay.waterDistant());
                }
            }
            out.writeInt(states.size());
            for (int state : states) { out.writeInt(state); out.writeUTF(stateName(state)); }
            for (var chunk : unique.keySet()) {
                var region = new ArrayList<>(Collections.nCopies(64, LostCityPreview.EMPTY));
                region.set(0, chunk);
                // The network codec can simplify oversized summaries. Disk input must
                // remain lossless, or its signature would describe different geometry.
                if (!LostCityPreview.bounded(region).equals(region)) throw new IOException("city cache budget");
                var buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    LostCityPreview.write(buffer, region);
                    int length = buffer.readableBytes(); out.writeInt(length);
                    buffer.readBytes(out, length);
                } finally { buffer.release(); }
                if (bytes.size() > PredictionMeshCodec.MAX_BYTES) throw new IOException("city cache budget");
            }
            for (var chunk : tile.chunks()) out.writeInt(unique.get(chunk));
        }
        return bytes.toByteArray();
    }

    static LostCityPreview.Tile decode(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int x = in.readInt(), z = in.readInt(), side = in.readInt();
            if (side < 1 || side > MAX_SIDE) throw new IOException("city cache extent");
            int count = in.readInt();
            if (count < 1 || count > side * side) throw new IOException("city cache dictionary");
            int states = in.readInt();
            if (states < 0 || states > bytes.length / 6) throw new IOException("city cache state count");
            for (int i = 0; i < states; i++) {
                int state = in.readInt();
                if (!stateName(state).equals(in.readUTF())) throw new IOException("city cache registry changed");
            }
            var unique = new ArrayList<LostCityPreview.Chunk>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 0 || length > in.available()) throw new IOException("city cache length");
                var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(in.readNBytes(length)));
                try {
                    var region = LostCityPreview.read(buffer);
                    if (buffer.isReadable()) throw new IOException("city cache trailing chunk bytes");
                    unique.add(region.get(0));
                } finally { buffer.release(); }
            }
            var chunks = new ArrayList<LostCityPreview.Chunk>(side * side);
            for (int i = 0; i < side * side; i++) {
                int index = in.readInt();
                if (index < 0 || index >= count) throw new IOException("city cache chunk index");
                chunks.add(unique.get(index));
            }
            if (in.available() != 0) throw new IOException("city cache trailing bytes");
            return new LostCityPreview.Tile(x, z, side, chunks);
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            throw new IOException("malformed city cache", malformed);
        }
    }

    private static String stateName(int id) throws IOException {
        var state = Block.stateById(id);
        if (id < 0 || state == null || Block.getId(state) != id) throw new IOException("city cache state");
        return state.toString();
    }

    private static void collect(Set<Integer> states, LostCityPreview.Model model) {
        if (model != null) for (int i = 0; i < model.quadCount(); i++) states.add(model.state(i));
    }

    static LostCityPreview.Tile buildings(LostCityPreview.Tile tile) {
        if (tile != null) for (var chunk : tile.chunks())
            if (chunk.kind() != 0 || !chunk.overlays().isEmpty()) return tile;
        return null;
    }

    /** Only hashes survive upload; don't retain another graph of city models per tile. */
    record Proof(int x, int z, int side, byte[] hashes) {
        static Proof of(LostCityPreview.Tile tile) {
            int minX = Math.floorDiv(tile.minChunkX(), 8), minZ = Math.floorDiv(tile.minChunkZ(), 8);
            int maxX = Math.floorDiv(tile.minChunkX() + tile.side() - 1, 8);
            int maxZ = Math.floorDiv(tile.minChunkZ() + tile.side() - 1, 8);
            byte[] hashes = new byte[(maxX - minX + 1) * (maxZ - minZ + 1) * 32];
            var unique = new HashMap<LostCityPreview.Chunk, byte[]>();
            for (int rz = minZ; rz <= maxZ; rz++) for (int rx = minX; rx <= maxX; rx++) {
                byte[] hash = regionHash(tile.minChunkX(), tile.minChunkZ(), tile.side(), rx, rz,
                        (cx, cz) -> tile.chunks().get((cz - tile.minChunkZ()) * tile.side() + cx - tile.minChunkX()), unique);
                System.arraycopy(hash, 0, hashes, ((rz - minZ) * (maxX - minX + 1) + rx - minX) * 32, 32);
            }
            return new Proof(tile.minChunkX(), tile.minChunkZ(), tile.side(), hashes);
        }

        boolean agrees(LostCityHintsS2CPayload response) {
            if (!response.active()) return false;
            int minX = Math.floorDiv(x, 8), minZ = Math.floorDiv(z, 8);
            int maxX = Math.floorDiv(x + side - 1, 8), maxZ = Math.floorDiv(z + side - 1, 8);
            int rx = response.regionX(), rz = response.regionZ();
            if (rx < minX || rx > maxX || rz < minZ || rz > maxZ) return true;
            byte[] next = regionHash(x, z, side, rx, rz,
                    (cx, cz) -> response.chunks().get((cz - rz * 8) * 8 + cx - rx * 8), new HashMap<>());
            int offset = ((rz - minZ) * (maxX - minX + 1) + rx - minX) * 32;
            return Arrays.equals(hashes, offset, offset + 32, next, 0, 32);
        }
    }

    private static byte[] regionHash(int x, int z, int side, int rx, int rz,
            java.util.function.BiFunction<Integer, Integer, LostCityPreview.Chunk> chunk,
            Map<LostCityPreview.Chunk, byte[]> unique) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int cz = Math.max(z, rz * 8); cz < Math.min(z + side, rz * 8 + 8); cz++)
                for (int cx = Math.max(x, rx * 8); cx < Math.min(x + side, rx * 8 + 8); cx++)
                    digest.update(unique.computeIfAbsent(chunk.apply(cx, cz), PredictionCityMeshCache::hash));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] hash(LostCityPreview.Chunk chunk) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            LostCityPreview.fingerprint(digest, new LostCityPreview.Tile(0, 0, 1, List.of(chunk)));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
