package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Data transfer only: no per-column density, material, biome or feature computation. */
final class RustWorldgenDocument {
    private static final Gson JSON = new Gson();

    static JsonObject create(JsonObject generator, JsonObject registries, ClientTerrainSampler context) throws IOException {
        JsonObject result = snapshot(generator,registries,context);
        colormaps().entrySet().forEach(entry->result.add(entry.getKey(),entry.getValue()));
        return result;
    }
    private static final java.util.concurrent.atomic.AtomicLong INPUT_GENERATION = new java.util.concurrent.atomic.AtomicLong();

    static void invalidateSharedInputs() { INPUT_GENERATION.incrementAndGet(); }

    /** Owned by one profile decode, never retained across worlds or registry snapshots. */
    static final class SharedInputs implements AutoCloseable {
        private long generation = Long.MIN_VALUE;
        private JsonObject definitions;
        private BlockState[] canonicalStates;
        private Map<JsonElement, BlockState> stateLookup;
        private JsonObject colors;
        private long palette;

        void prepare() {
            long current = INPUT_GENERATION.get();
            if (generation == current && definitions != null) return;
            close();
            definitions = blockDefinitions();
            var list = new ArrayList<BlockState>();
            for (Block block : BuiltInRegistries.BLOCK) list.addAll(block.getStateDefinition().getPossibleStates());
            canonicalStates = list.toArray(BlockState[]::new);
            stateLookup = null;
            colors = null;
            generation = current;
        }
        Map<JsonElement, BlockState> stateLookup() {
            if (stateLookup == null) {
                stateLookup = new HashMap<>();
                for (BlockState state : canonicalStates) stateLookup.put(encodeState(state), state);
            }
            return stateLookup;
        }
        BlockState[] canonicalStates() { return canonicalStates; }
        JsonObject compactPalette() {
            prepare();
            JsonArray groups = new JsonArray();
            for (Block block : BuiltInRegistries.BLOCK) {
                var properties = new ArrayList<Property<?>>(block.getStateDefinition().getProperties());
                var choices = new ArrayList<List<String>>();
                JsonArray keys = new JsonArray(), values = new JsonArray(), codes = new JsonArray();
                for (Property<?> property : properties) {
                    keys.add(property.getName());
                    List<String> names = values(property);
                    choices.add(names); values.add(JSON.toJsonTree(names));
                }
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    long code = 0, factor = 1;
                    for (int i = 0; i < properties.size(); i++) {
                        int index = choices.get(i).indexOf(value(state, properties.get(i)));
                        if (index < 0) throw new IllegalArgumentException("Missing canonical state value");
                        code = Math.addExact(code, Math.multiplyExact(factor, index));
                        factor = Math.multiplyExact(factor, choices.get(i).size());
                    }
                    codes.add(code);
                }
                JsonObject group = new JsonObject();
                group.addProperty("name", BuiltInRegistries.BLOCK.getKey(block).toString());
                group.add("properties", keys); group.add("values", values); group.add("codes", codes);
                groups.add(group);
            }
            JsonObject doc = new JsonObject();
            doc.add("block_definitions", definitions); doc.add("groups", groups);
            return doc;
        }
        long palette() {
            prepare();
            if (palette == 0) {
                var timing = new PredictionInitializationTiming("sharedPalette");
                JsonObject compact = compactPalette(); timing.mark("encode");
                String serialized = compact.toString(); timing.mark("serialize");
                palette = RustWorldgenBackend.openPalette(serialized); timing.mark("nativeCreate");
                if (palette == 0) throw new IllegalStateException("Missing native palette");
                if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging)
                    dev.xantha.vss.common.VSSLogger.debug("VSS shared palette states=" + canonicalStates.length
                            + ", chars=" + serialized.length());
                timing.finish();
            }
            return palette;
        }
        JsonObject colors() throws IOException {
            if (colors == null) colors = colormaps();
            return colors;
        }
        @Override public void close() {
            if (palette != 0) { RustWorldgenBackend.closePalette(palette); palette = 0; }
            definitions = null; canonicalStates = null; stateLookup = null; colors = null;
        }
    }

    static JsonObject create(JsonObject generator, JsonObject registries, ClientTerrainSampler context,
            SharedInputs shared) throws IOException {
        JsonObject result = snapshot(generator, registries, context, shared, true);
        shared.colors().entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
    }

    /** Vanilla state schema, using the registry's canonical property names and values. */
    static JsonObject encodeState(BlockState state) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Property<?> property : state.getProperties())
                properties.addProperty(property.getName(), value(state, property));
            encoded.add("Properties", properties);
        }
        return encoded;
    }

    static JsonObject colormaps() throws IOException {
        JsonObject result=new JsonObject();
        for (String name : List.of("grass", "foliage")) {
            var resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(
                    ResourceLocation.withDefaultNamespace("textures/colormap/" + name + ".png"));
            try (var input = resource.open()) {
                var image = javax.imageio.ImageIO.read(input);
                if (image == null || image.getWidth() != 256 || image.getHeight() != 256)
                    throw new IOException("Invalid " + name + " colormap");
                result.add(name + "_colormap", JSON.toJsonTree(Arrays.stream(image.getRGB(0, 0, 256, 256, null, 0, 256))
                        .mapToLong(Integer::toUnsignedLong).toArray()));
            }
        }
        return result;
    }

    static JsonObject snapshot(JsonObject generator,JsonObject registries,ClientTerrainSampler context) {
        return snapshot(generator, registries, context, new SharedInputs());
    }

    static JsonObject snapshot(JsonObject generator, JsonObject registries, ClientTerrainSampler context,
            SharedInputs shared) {
        return snapshot(generator, registries, context, shared, false);
    }
    private static JsonObject snapshot(JsonObject generator, JsonObject registries, ClientTerrainSampler context,
            SharedInputs shared, boolean compact) {
        shared.prepare();
        // Fields below are read-only inputs. Copy the top-level object only; nested generator
        // and registry trees are neither mutated here nor by JNI (which receives a string).
        JsonObject result = new JsonObject();
        generator.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        if (registries.has("custom_registries")) result.add("custom_registries", registries.get("custom_registries"));
        for (String name : List.of("density_functions", "noises", "biomes", "configured_features", "placed_features")) {
            if (!registries.has(name)) throw new IllegalArgumentException("Missing native registry " + name);
            result.add(name, registries.get(name));
        }
        JsonArray possible = new JsonArray();
        context.generatorContext().getBiomeSource().possibleBiomes().forEach(b -> possible.add(
                b.unwrapKey().orElseThrow(() -> new IllegalArgumentException("Inline biome needs a native identity")).location().toString()));
        result.add("possible_biomes", possible);
        if (compact) {
            result.addProperty("vss_shared_palette", shared.palette());
            return result;
        }
        result.add("block_definitions", shared.definitions);
        JsonArray states = new JsonArray();
        for (BlockState state : shared.canonicalStates()) states.add(encodeState(state));
        result.add("input_states", states);
        return result;
    }

    static JsonObject blockDefinitions() {
        JsonObject blocks = new JsonObject();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            Map<String, String> defaults = new TreeMap<>();
            Map<String, List<String>> properties = new TreeMap<>();
            for (Property<?> property : state.getProperties()) {
                defaults.put(property.getName(), value(state, property));
                properties.put(property.getName(), values(property));
            }
            blocks.add(BuiltInRegistries.BLOCK.getKey(block).toString(), JSON.toJsonTree(Map.of(
                    "defaults", defaults, "properties", properties,
                    "tags", state.getTags().map(t -> t.location().toString()).sorted().toList(),
                    "air", state.isAir(), "fluid", !state.getFluidState().isEmpty(),
                    "motion_blocking", state.blocksMotion(), "double_plant", block instanceof DoublePlantBlock,
                    "support_faces",supportFaces(state),"shape_update",shapeUpdate(block))));
        }
        return blocks;
    }
    static int supportFaces(BlockState state) {
        int bits=0;
        for(var direction:net.minecraft.core.Direction.values()) {
            if(net.minecraft.world.level.block.MultifaceBlock.canAttachTo(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    direction,net.minecraft.core.BlockPos.ZERO,state))bits|=1<<direction.ordinal();
        }
        return bits;
    }
    private static String shapeUpdate(Block block) {
        if(block instanceof net.minecraft.world.level.block.VineBlock)return "vine";
        if(block instanceof net.minecraft.world.level.block.CocoaBlock)return "cocoa";
        if(block instanceof net.minecraft.world.level.block.CarpetBlock)return "carpet";
        if(block instanceof net.minecraft.world.level.block.HugeMushroomBlock)return "huge_mushroom";
        if(block instanceof net.minecraft.world.level.block.MangrovePropaguleBlock)return "propagule";
        if(block instanceof DoublePlantBlock)return "double_plant";
        if(block instanceof net.minecraft.world.level.block.BushBlock)return "bush";
        if(block instanceof net.minecraft.world.level.block.SnowyDirtBlock)return "snowy";
        if(block instanceof net.minecraft.world.level.block.LeavesBlock || block instanceof net.minecraft.world.level.block.MangroveRootsBlock
                || block instanceof net.minecraft.world.level.block.LiquidBlock
                || block instanceof net.minecraft.world.level.block.BeehiveBlock)return "none";
        for(Class<?> type=block.getClass();type!=null && type!=Block.class;type=type.getSuperclass()) {
            for(var method:type.getDeclaredMethods())if(method.getName().equals("updateShape"))return "compatibility";
        }
        return "none";
    }
    private static <T extends Comparable<T>> String value(BlockState state, Property<T> p) { return p.getName(state.getValue(p)); }
    private static <T extends Comparable<T>> List<String> values(Property<T> p) { return p.getPossibleValues().stream().map(p::getName).toList(); }
    private RustWorldgenDocument() { }
}
