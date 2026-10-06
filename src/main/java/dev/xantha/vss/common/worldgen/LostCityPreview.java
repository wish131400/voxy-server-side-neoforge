package dev.xantha.vss.common.worldgen;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable, bounded exterior summaries. These never contain generated chunks or interiors. */
public final class LostCityPreview {
    public static final int REGION_SIDE = 8, REGION_ENTRIES = 64;
    public static final int MAX_MODELS = 1024, MAX_QUADS = 49_152, MAX_MODEL_QUADS = 4096;
    public static final int MAX_HEIGHT = 1024, MAX_PLACEMENTS = 256;
    public static final int ROAD = 1, BUILDING = 2, PARK = 3;
    public static final Chunk EMPTY = new Chunk(0, 0, false, 0, 0, 0, 0, List.of(), null);

    private LostCityPreview() { }

    /** Three words per rectangle: origin, extent/direction, synced Minecraft block-state ID. */
    public static final class Model {
        private final int[] words;
        private final int height, hash;

        public Model(int[] words) {
            if (words.length % 3 != 0 || words.length / 3 > MAX_MODEL_QUADS)
                throw new IllegalArgumentException("Lost Cities model size");
            this.words = words.clone();
            int max = 0;
            for (int i = 0; i < quadCount(); i++) {
                int x = x(i), z = z(i), y = y(i), dx = dx(i), dz = dz(i), dy = dy(i), d = direction(i);
                if (x + dx > 16 || z + dz > 16 || y + dy > MAX_HEIGHT || d > 5
                        || (d == 0 || d == 5) && (dx == 0 || dz == 0 || dy != 0)
                        || (d == 1 || d == 2) && (dx == 0 || dz != 0 || dy == 0)
                        || (d == 3 || d == 4) && (dz == 0 || dx != 0 || dy == 0)
                        || state(i) < 0 || state(i) > 1_048_576)
                    throw new IllegalArgumentException("Lost Cities exterior rectangle");
                max = Math.max(max, y + dy);
            }
            height = max;
            hash = Arrays.hashCode(this.words);
        }

        public int quadCount() { return words.length / 3; }
        public int height() { return height; }
        public int x(int i) { return words[i * 3] & 31; }
        public int z(int i) { return words[i * 3] >>> 5 & 31; }
        public int y(int i) { return words[i * 3] >>> 10 & 2047; }
        public int dx(int i) { return words[i * 3 + 1] & 31; }
        public int dz(int i) { return words[i * 3 + 1] >>> 5 & 31; }
        public int dy(int i) { return words[i * 3 + 1] >>> 10 & 2047; }
        public int direction(int i) { return words[i * 3 + 1] >>> 21; }
        public int state(int i) { return words[i * 3 + 2]; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Model model && Arrays.equals(words, model.words);
        }
    }

    public record Placement(int y, Model model) {
        public Placement {
            Objects.requireNonNull(model);
            if (y < 0 || y + model.height() > MAX_HEIGHT)
                throw new IllegalArgumentException("Lost Cities floor height");
        }
    }

    /** Absolute-height infrastructure; it neither flattens terrain nor owns city vegetation. */
    public record Overlay(int y, Model model, Model distant, Model water, Model waterDistant) {
        public Overlay {
            Objects.requireNonNull(model); Objects.requireNonNull(distant);
            if (y < Short.MIN_VALUE || y > Short.MAX_VALUE || (water == null) != (waterDistant == null))
                throw new IllegalArgumentException("Lost Cities infrastructure");
        }
        Overlay distantOnly() { return new Overlay(y, distant, distant, waterDistant, waterDistant); }
    }

    /** Connection bits: west, east, north, south. Surface and pavement use real style palettes. */
    public record Chunk(int kind, int ground, boolean flatten, int surfaceState, int pavementState,
                        int roadWidth, int connections, List<Placement> floors, Model silhouette, List<Overlay> overlays) {
        public Chunk(int kind, int ground, boolean flatten, int surfaceState, int pavementState,
                     int roadWidth, int connections, List<Placement> floors, Model silhouette) {
            this(kind, ground, flatten, surfaceState, pavementState, roadWidth, connections, floors, silhouette, List.of());
        }
        public Chunk {
            floors = List.copyOf(floors);
            overlays = List.copyOf(overlays);
            if (kind < 0 || kind > PARK || ground < Short.MIN_VALUE || ground > Short.MAX_VALUE
                    || roadWidth < 0 || roadWidth > 16 || connections < 0 || connections > 15
                    || floors.size() > MAX_PLACEMENTS || overlays.size() > 32 || surfaceState < 0 || surfaceState > 1_048_576
                    || pavementState < 0 || pavementState > 1_048_576)
                throw new IllegalArgumentException("Lost Cities chunk summary");
        }
        public int top() {
            int height = silhouette == null ? 1 : silhouette.height();
            for (Placement floor : floors) height = Math.max(height, floor.y + floor.model.height());
            return ground + height;
        }
        public boolean building() { return kind == BUILDING; }
        public Chunk withOverlays(List<Overlay> additions) {
            return new Chunk(kind, ground, flatten, surfaceState, pavementState, roadWidth, connections, floors, silhouette, additions);
        }
        Chunk distantOnly() { return new Chunk(kind, ground, flatten, surfaceState, pavementState,
                roadWidth, connections, List.of(), silhouette, overlays.stream().map(Overlay::distantOnly).toList()); }
    }

    public record Tile(int minChunkX, int minChunkZ, int side, List<Chunk> chunks) {
        public Tile {
            chunks = List.copyOf(chunks);
            if (side < 1 || chunks.size() != side * side) throw new IllegalArgumentException("City tile");
        }
        public Chunk atBlock(int x, int z) {
            int cx = Math.floorDiv(x, 16) - minChunkX, cz = Math.floorDiv(z, 16) - minChunkZ;
            return cx < 0 || cz < 0 || cx >= side || cz >= side ? EMPTY : chunks.get(cz * side + cx);
        }
    }

    public static List<Chunk> bounded(List<Chunk> chunks) {
        if (chunks.size() != REGION_ENTRIES) throw new IllegalArgumentException("City region");
        Set<Model> models = models(chunks);
        if (models.size() > MAX_MODELS || models.stream().mapToInt(Model::quadCount).sum() > MAX_QUADS)
            chunks = chunks.stream().map(Chunk::distantOnly).toList();
        models = models(chunks);
        if (models.size() > MAX_MODELS || models.stream().mapToInt(Model::quadCount).sum() > MAX_QUADS)
            throw new IllegalArgumentException("City summary budget");
        return List.copyOf(chunks);
    }

    private static Set<Model> models(List<Chunk> chunks) {
        Set<Model> result = new LinkedHashSet<>();
        for (Chunk chunk : chunks) {
            if (chunk.silhouette != null) result.add(chunk.silhouette);
            for (Placement floor : chunk.floors) result.add(floor.model);
            for (Overlay overlay : chunk.overlays) {
                result.add(overlay.model); result.add(overlay.distant);
                if (overlay.water != null) { result.add(overlay.water); result.add(overlay.waterDistant); }
            }
        }
        return result;
    }

    public static long retainedBytes(List<Chunk> chunks) {
        Set<Model> models = Collections.newSetFromMap(new IdentityHashMap<>());
        long bytes = 128L * chunks.size();
        for (Chunk chunk : chunks) {
            if (chunk.silhouette != null) models.add(chunk.silhouette);
            bytes += 32L * chunk.floors.size();
            for (Placement floor : chunk.floors) models.add(floor.model);
            bytes += 64L * chunk.overlays.size();
            for (Overlay overlay : chunk.overlays) {
                models.add(overlay.model); models.add(overlay.distant);
                if (overlay.water != null) { models.add(overlay.water); models.add(overlay.waterDistant); }
            }
        }
        for (Model model : models) bytes += 128L + model.quadCount() * 12L;
        return bytes;
    }

    public static void write(FriendlyByteBuf out, List<Chunk> chunks) {
        chunks = bounded(chunks);
        Map<Model, Integer> indices = new LinkedHashMap<>();
        for (Model model : models(chunks)) indices.put(model, indices.size());
        out.writeVarInt(indices.size());
        for (Model model : indices.keySet()) {
            out.writeVarInt(model.quadCount());
            for (int i = 0; i < model.quadCount(); i++) {
                out.writeInt(model.words[i * 3]); out.writeInt(model.words[i * 3 + 1]);
                out.writeVarInt(model.state(i));
            }
        }
        for (Chunk chunk : chunks) {
            out.writeByte(chunk.kind); out.writeShort(chunk.ground); out.writeBoolean(chunk.flatten);
            out.writeVarInt(chunk.surfaceState); out.writeVarInt(chunk.pavementState);
            out.writeByte(chunk.roadWidth); out.writeByte(chunk.connections);
            out.writeVarInt(chunk.silhouette == null ? 0 : indices.get(chunk.silhouette) + 1);
            out.writeVarInt(chunk.floors.size());
            for (Placement floor : chunk.floors) {
                out.writeVarInt(floor.y); out.writeVarInt(indices.get(floor.model));
            }
            out.writeVarInt(chunk.overlays.size());
            for (Overlay overlay : chunk.overlays) {
                out.writeShort(overlay.y);
                for (Model model : new Model[]{overlay.model, overlay.distant, overlay.water, overlay.waterDistant})
                    out.writeVarInt(model == null ? 0 : indices.get(model) + 1);
            }
        }
    }

    public static List<Chunk> read(FriendlyByteBuf in) {
        int count = limit(in.readVarInt(), MAX_MODELS), total = 0;
        Model[] models = new Model[count];
        for (int m = 0; m < count; m++) {
            int quads = limit(in.readVarInt(), MAX_MODEL_QUADS);
            total = limit(total + quads, MAX_QUADS);
            int[] words = new int[quads * 3];
            for (int i = 0; i < quads; i++) {
                words[i * 3] = in.readInt(); words[i * 3 + 1] = in.readInt();
                words[i * 3 + 2] = in.readVarInt();
            }
            models[m] = new Model(words);
        }
        List<Chunk> chunks = new ArrayList<>(REGION_ENTRIES);
        for (int i = 0; i < REGION_ENTRIES; i++) {
            int kind = in.readUnsignedByte(), ground = in.readShort(); boolean flatten = in.readBoolean();
            int surface = in.readVarInt(), pavement = in.readVarInt();
            int width = in.readUnsignedByte(), connections = in.readUnsignedByte();
            int silhouette = limit(in.readVarInt(), count);
            int floors = limit(in.readVarInt(), MAX_PLACEMENTS);
            if (floors > 0 && count == 0)
                throw new IllegalArgumentException("City floor references missing model");
            List<Placement> placements = new ArrayList<>(floors);
            for (int f = 0; f < floors; f++) {
                int y = limit(in.readVarInt(), MAX_HEIGHT);
                int model = in.readVarInt();
                if (model < 0 || model >= count)
                    throw new IllegalArgumentException("City floor model index");
                placements.add(new Placement(y, models[model]));
            }
            int overlays = limit(in.readVarInt(), 32);
            List<Overlay> additions = new ArrayList<>(overlays);
            for (int o = 0; o < overlays; o++) {
                int y = in.readShort(); Model[] refs = new Model[4];
                for (int j = 0; j < 4; j++) {
                    int ref = limit(in.readVarInt(), count);
                    if (j < 2 && ref == 0) throw new IllegalArgumentException("Missing infrastructure model");
                    refs[j] = ref == 0 ? null : models[ref - 1];
                }
                additions.add(new Overlay(y, refs[0], refs[1], refs[2], refs[3]));
            }
            chunks.add(new Chunk(kind, ground, flatten, surface, pavement, width, connections,
                    placements, silhouette == 0 ? null : models[silhouette - 1], additions));
        }
        return List.copyOf(chunks);
    }

    private static int limit(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("City summary bounds");
        return value;
    }

    public static int origin(int x, int y, int z) { return x | z << 5 | y << 10; }
    public static int extent(int dx, int dy, int dz, int direction) {
        return dx | dz << 5 | dy << 10 | direction << 21;
    }

    public static void fingerprint(java.security.MessageDigest digest, Tile tile) {
        var data = java.nio.ByteBuffer.allocate(4);
        java.util.function.IntConsumer word = n -> { data.clear(); data.putInt(n); digest.update(data.array()); };
        word.accept(0x4C430004); word.accept(tile.minChunkX); word.accept(tile.minChunkZ); word.accept(tile.side);
        for (Chunk chunk : tile.chunks) {
            word.accept(chunk.kind); word.accept(chunk.ground); word.accept(chunk.flatten ? 1 : 0);
            word.accept(chunk.surfaceState); word.accept(chunk.pavementState); word.accept(chunk.roadWidth);
            word.accept(chunk.connections); word.accept(chunk.floors.size());
            for (Placement floor : chunk.floors) { word.accept(floor.y); hashModel(word, floor.model); }
            hashModel(word, chunk.silhouette);
            word.accept(chunk.overlays.size());
            for (Overlay overlay : chunk.overlays) {
                word.accept(overlay.y); hashModel(word, overlay.model); hashModel(word, overlay.distant);
                hashModel(word, overlay.water); hashModel(word, overlay.waterDistant);
            }
        }
    }

    private static void hashModel(java.util.function.IntConsumer word, Model model) {
        word.accept(model == null ? -1 : model.words.length);
        if (model != null) for (int value : model.words) word.accept(value);
    }
}
