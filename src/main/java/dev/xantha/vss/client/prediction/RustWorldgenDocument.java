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
    static final class SharedInputs {
        private long generation = Long.MIN_VALUE;
        private JsonObject definitions;
        private JsonArray states;
        private Map<JsonElement, BlockState> stateLookup;
        private JsonObject colors;

        void prepare() {
            long current = INPUT_GENERATION.get();
            if (generation == current && definitions != null) return;
            definitions = blockDefinitions();
            states = new JsonArray();
            stateLookup = new HashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    JsonObject encoded = encodeState(state);
                    states.add(encoded);
                    stateLookup.put(encoded, state);
                }
            }
            colors = null;
            generation = current;
        }

        Map<JsonElement, BlockState> stateLookup() { return stateLookup; }

        JsonObject colors() throws IOException {
            if (colors == null) colors = colormaps();
            return colors;
        }
    }

    static JsonObject create(JsonObject generator, JsonObject registries, ClientTerrainSampler context,
            SharedInputs shared) throws IOException {
        JsonObject result = snapshot(generator, registries, context, shared);
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
        result.add("block_definitions", shared.definitions);
        result.add("input_states", shared.states);
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
