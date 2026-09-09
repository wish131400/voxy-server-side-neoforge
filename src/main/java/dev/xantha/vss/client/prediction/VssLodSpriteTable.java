package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.block.Block;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Render-thread sprite table backing the per-block detail path.
 *
 * <p>Each column's top block resolves to a baked model sprite; the sprite
 * rectangles are uploaded as a texture so the fragment stage can map
 * every block cell of a detail LOD quad onto the real block texture (the
 * {@code UseAverage == 0} path). VSS stores one rectangle per block id
 * encountered by mesh workers; the vertex colour's alpha byte carries the
 * 1-based sprite index. {@link #FLAT} is the unavailable-material fallback.</p>
 */
final class VssLodSpriteTable {
    /** Alpha value that keeps the flat colour path. */
    static final int FLAT = 255;
    // Sprite ids are carried in the vertex colour alpha byte until they are
    // expanded into the packed quad's 16-bit attribute. Keep 255 reserved
    // for FLAT and refuse rows that cannot make that round trip; wrapping a
    // row back to an unrelated grass/snow sprite produces convincing-looking
    // but completely wrong streaks.
    private static final int MAX_SPRITES = 254;
    private static final Map<ResourceLocation, Integer> INDEX_BY_SPRITE = new HashMap<>();
    // Read from worker threads (mesh building) while the render thread
    // resolves new blocks, so the maps must be concurrent.  Two rows per
    // block: the up-face sprite (terrain tops) and the side/particle sprite
    // (walls, trunks — vanilla's grass block would otherwise put dirt on
    // every predicted hilltop, since its particle texture is dirt).
    private static final Map<Integer, Integer> INDEX_BY_BLOCK =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Integer> SIDE_INDEX_BY_BLOCK =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Direction-specific side rows.  A block model is allowed to use a
     * different sprite on each of its four vertical faces (logs, glazed
     * terracotta and modded directional blocks commonly do), so a single
     * particle row is not a valid wall material. */
    private static final Map<Long, Integer> SIDE_INDEX_BY_FACE_BLOCK =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Sprite rows for the fluid kinds (1 water, 2 lava, 3 ice), resolved
     * straight from the atlas so predicted fluids ride the real animated
     * fluid textures and inherit water's translucency from the texture's
     * own alpha channel.
     */
    private static final Map<Long, Integer> INDEX_BY_STATE_FACE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int[] FLUID_SPRITES = new int[4];
    private static final List<float[]> RECTS = new ArrayList<>();
    /** Alpha-weighted average colours per sprite, row 1 of the texture. */
    private static final List<float[]> AVERAGES = new ArrayList<>();
    /** Whether each registered row contains transparent pixels. */
    private static final List<Boolean> CUTOUTS = new ArrayList<>();
    private static final List<float[]> MODEL_UVS = new ArrayList<>();
    private static final Map<ModelUvKey, Integer> MODEL_ROWS = new HashMap<>();
    private static final Map<Integer, Integer> MODEL_BLOCKS = new HashMap<>();
    private static volatile byte[] modelFlags = new byte[256];
    private record ModelUvKey(ResourceLocation sprite, List<Float> uv, boolean shade, int blockId) { }
    /**
     * CPU-side mirror of {@link #AVERAGES} as ARGB ints, published when the
     * GPU table rebuilds so the column palette can multiply real texture
     * average colours instead of registry single-point tints.  Null until
     * the first table build; entries default to white.
     */
    private static volatile int[] averageColorsArgb;
    private static volatile boolean seeded;
    /** Set on the render thread once the atlas table has been initialized. */
    private static volatile boolean runtimeReady;
    /**
     * 0 until the first publish of real texture averages, 1 after.  Meshes
     * built before publication carry registry fallback colours; the manager
     * may compare this to decide a one-time rebuild.
     */
    private static volatile int averageGeneration;

    /** See {@link #averageGeneration}. */
    static int averageGeneration() {
        return averageGeneration;
    }

    /** Compact render-thread diagnostics; callers emit this only in debug summaries. */
    static String diagnostics() {
        synchronized (VssLodSpriteTable.class) {
            return "rows=" + RECTS.size() + ",ready=" + runtimeReady
                    + ",broken=" + broken + ",dirty=" + dirty
                    + ",generation=" + averageGeneration;
        }
    }

    /**
     * Registers the representative terrain blocks once so the table (and
     * the CPU colour mirror) carries real averages before the first tile
     * mesh builds — the visible prediction band is coarse spacing, which
     * never passes through the detail-texture path that used to be the
     * only registration trigger.
     */
    private static void seedRepresentativeBlocks() {
        if (seeded || broken) {
            return;
        }
        seeded = true;
        for (int blockId : PredictionMaterialPalette.seedBlockIds()) {
            if (blockId != Integer.MIN_VALUE) {
                indexForBlock(blockId);
                sideIndexForBlock(blockId);
            }
        }
    }
    private static TextureAtlasSprite atlasToken;
    private static int textureId = -1;
    private static volatile boolean dirty = true;
    private static boolean broken;

    private VssLodSpriteTable() {
    }

    static boolean available() {
        return !broken;
    }

    /** Representative block for each baked row, used for Iris block.properties ids. */
    static synchronized int[] materialBlocks() {
        int[] blocks = new int[256];
        java.util.Arrays.fill(blocks, -1);
        INDEX_BY_BLOCK.forEach((block, row) -> {
            if (row > 0 && row < FLAT && blocks[row] == -1) blocks[row] = block;
        });
        SIDE_INDEX_BY_BLOCK.forEach((block, row) -> {
            if (row > 0 && row < FLAT && blocks[row] == -1) blocks[row] = block;
        });
        SIDE_INDEX_BY_FACE_BLOCK.forEach((key, row) -> {
            if (row > 0 && row < FLAT && blocks[row] == -1) blocks[row] = (int) (key >> 3);
        });
        INDEX_BY_STATE_FACE.forEach((key, row) -> {
            if (row > 0 && row < FLAT && blocks[row] == -1)
                blocks[row] = BuiltInRegistries.BLOCK.getId(Block.stateById((int) (key >> 3)).getBlock());
        });
        MODEL_BLOCKS.forEach((row, block) -> blocks[row] = block);
        for (int kind = 1; kind <= 3; kind++) {
            int row = FLUID_SPRITES[kind];
            if (row > 0 && row < FLAT) blocks[row] = BuiltInRegistries.BLOCK.getId(switch (kind) {
                case 1 -> net.minecraft.world.level.block.Blocks.WATER;
                case 2 -> net.minecraft.world.level.block.Blocks.LAVA;
                default -> net.minecraft.world.level.block.Blocks.ICE;
            });
        }
        return blocks;
    }

    /** Populate CPU appearances before the first mesh worker is scheduled. */
    static void prepare() {
        if (runtimeReady || broken) return;
        runtimeReady = true;
        seedRepresentativeBlocks();
    }

    /**
     * Resets when the atlas is re-stitched. CPU and GPU meshes carrying old
     * row indices must both be invalidated when this returns true.
     */
    static boolean refresh(TextureAtlasSprite token) {
        if (token == null || token == atlasToken) {
            return false;
        }
        atlasToken = token;
        synchronized (VssLodSpriteTable.class) {
            runtimeReady = false;
            INDEX_BY_BLOCK.clear();
            SIDE_INDEX_BY_BLOCK.clear();
            SIDE_INDEX_BY_FACE_BLOCK.clear();
            INDEX_BY_STATE_FACE.clear();
            INDEX_BY_SPRITE.clear();
            java.util.Arrays.fill(FLUID_SPRITES, 0);
            RECTS.clear();
            AVERAGES.clear();
            CUTOUTS.clear();
            MODEL_UVS.clear();
            MODEL_ROWS.clear();
            MODEL_BLOCKS.clear();
            modelFlags = new byte[256];
            averageColorsArgb = null;
            seeded = false;
            broken = false;
            dirty = true;
        }
        return true;
    }

    /**
     * Returns the up-face sprite index for a block id, resolving it lazily.
     * Mesh workers resolve feature blocks (leaves, logs, plants) on their own
     * threads, so the resolve path is serialised: RECTS and AVERAGES are
     * plain lists that the render thread snapshots while rebuilding.
     */
    static int indexForBlock(int blockId) {
        if (!runtimeReady) {
            return FLAT;
        }
        return spriteIndex(blockId, 0);
    }

    /**
     * The side/particle sprite for a block id — walls, trunk boxes and
     * plant crosses sample this; vanilla's columnar blocks (logs) and
     * composite blocks (grass) have distinct side textures.
     */
    static int sideIndexForBlock(int blockId) {
        if (!runtimeReady) {
            return FLAT;
        }
        return spriteIndex(blockId, -1);
    }

    /**
     * Returns the sprite for a the face id: 1 north, 2 south, 3 west,
     * 4 east.  The unculled model quads are used only when the directional
     * list is empty, matching {@code LodBlockPalette.sideLayers}.
     */
    static int sideIndexForBlock(int blockId, int face) {
        if (!runtimeReady) {
            return FLAT;
        }
        if (face < 1 || face > 4) {
            return sideIndexForBlock(blockId);
        }
        return spriteIndex(blockId, face);
    }

    static int indexForState(net.minecraft.world.level.block.state.BlockState state, int face) {
        if (!runtimeReady || broken || state == null) return FLAT;
        long key = ((long) Block.getId(state) << 3) | (face & 7);
        Integer cached = INDEX_BY_STATE_FACE.get(key);
        if (cached != null) return cached;
        synchronized (VssLodSpriteTable.class) {
            return INDEX_BY_STATE_FACE.computeIfAbsent(key, ignored -> resolve(state, face));
        }
    }

    static int averageForState(net.minecraft.world.level.block.state.BlockState state, int face) {
        int sprite = indexForState(state, face);
        return sprite == FLAT ? 0 : averageForSprite(sprite);
    }

    private static int spriteIndex(int blockId, int face) {
        if (broken) {
            return FLAT;
        }
        if (blockId == ClientColumnSample.NO_BLOCK) {
            return FLAT;
        }
        Integer cached;
        if (face == 0) {
            cached = INDEX_BY_BLOCK.get(blockId);
            if (cached != null) {
                return cached;
            }
        } else if (face < 0) {
            cached = SIDE_INDEX_BY_BLOCK.get(blockId);
            if (cached != null) {
                return cached;
            }
        } else {
            cached = SIDE_INDEX_BY_FACE_BLOCK.get(faceKey(blockId, face));
            if (cached != null) {
                return cached;
            }
        }
        if (cached != null) {
            return cached;
        }
        synchronized (VssLodSpriteTable.class) {
            if (face == 0) {
                cached = INDEX_BY_BLOCK.get(blockId);
            } else if (face < 0) {
                cached = SIDE_INDEX_BY_BLOCK.get(blockId);
            } else {
                cached = SIDE_INDEX_BY_FACE_BLOCK.get(faceKey(blockId, face));
            }
            if (cached != null) {
                return cached;
            }
            int index = resolve(blockId, face);
            if (face == 0) {
                INDEX_BY_BLOCK.put(blockId, index);
            } else if (face < 0) {
                SIDE_INDEX_BY_BLOCK.put(blockId, index);
            } else {
                SIDE_INDEX_BY_FACE_BLOCK.put(faceKey(blockId, face), index);
            }
            return index;
        }
    }

    private static long faceKey(int blockId, int face) {
        return ((long) blockId << 3) ^ (face & 7L);
    }

    /**
     * Sprite row for a fluid kind (1 water, 2 lava, 3 ice).  The fluid
     * appearance is exactly the atlas sprite — animated water included — so
     * the fragment samples it through the normal sprite path and water's
     * baked ~0.7 alpha supplies the translucency.  Returns 0 while
     * unavailable; the caller then falls back to the flat tint.
     */
    static int fluidSpriteIndex(int kind) {
        if (broken || kind < 1 || kind > 3) {
            return 0;
        }
        int cached = FLUID_SPRITES[kind];
        if (cached != 0) {
            return cached;
        }
        synchronized (VssLodSpriteTable.class) {
            if (FLUID_SPRITES[kind] != 0) {
                return FLUID_SPRITES[kind];
            }
            ResourceLocation id = switch (kind) {
                case 1 -> ResourceLocation.withDefaultNamespace("block/water_still");
                case 2 -> ResourceLocation.withDefaultNamespace("block/lava_still");
                default -> ResourceLocation.withDefaultNamespace("block/ice");
            };
            try {
                TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager()
                        .getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(id);
                if (sprite == null) {
                    return 0;
                }
                int index = registerSprite(sprite);
                if (index == FLAT) return 0;
                FLUID_SPRITES[kind] = index;
                return index;
            } catch (Throwable failure) {
                return 0;
            }
        }
    }

    /**
     * Alpha-weighted average colour of a registered block's particle sprite,
     * as ARGB, or 0 when the block has no table entry yet.  Callers fall back
     * to their own palette for 0.  Averages are computed on the render thread
     * when the GPU table rebuilds; blocks resolved after that build read the
     * newly appended entries through the lazy path below.
     */
    static int averageColorArgb(int blockId) {
        return averageColorArgb(blockId, 0);
    }

    static int fluidAverageColorArgb(int kind) {
        int sprite = fluidSpriteIndex(kind);
        return sprite == 0 ? 0 : averageForSprite(sprite);
    }

    /** Average for one concrete the face.  Side materials must use the
     * same face sprite that the shader samples; using the top average for a
     * log, for example, over-amplifies the bark when the end grain is dark. */
    static int averageColorArgb(int blockId, int face) {
        if (broken) {
            return 0;
        }
        Integer index = face == 0
                ? INDEX_BY_BLOCK.get(blockId)
                : face < 0
                ? SIDE_INDEX_BY_BLOCK.get(blockId)
                : SIDE_INDEX_BY_FACE_BLOCK.get(faceKey(blockId, face));
        if (index == null || index == FLAT) {
            return 0;
        }
        return averageForSprite(index);
    }

    private static int averageForSprite(int index) {
        int[] colors = averageColorsArgb;
        // Mesh workers can resolve a block after the row was appended but
        // before the render thread publishes averageColorsArgb during the
        // next GPU table rebuild. Read the already computed source-image
        // average directly in that window so the tile never bakes a guessed
        // palette color merely because it finished one frame early.
        if (colors != null && index <= colors.length) {
            return colors[index - 1];
        }
        synchronized (VssLodSpriteTable.class) {
            int row = index - 1;
            if (row < 0 || row >= AVERAGES.size()) {
                return 0;
            }
            float[] average = AVERAGES.get(row);
            if (average == null || average[3] <= 0.0F) {
                return 0;
            }
            return 0xFF000000
                    | clamp255(average[0]) << 16
                    | clamp255(average[1]) << 8
                    | clamp255(average[2]);
        }
    }

    /** Returns the transparency flag for a registered row. */
    static boolean isCutout(int sprite) {
        if (sprite <= 0 || sprite > MAX_SPRITES) {
            return false;
        }
        synchronized (VssLodSpriteTable.class) {
            int index = sprite - 1;
            return index < CUTOUTS.size() && CUTOUTS.get(index);
        }
    }

    /** Rebuilds the CPU-side ARGB mirror from the float averages. */
    private static void publishAverageColors() {
        int[] colors = new int[Math.max(0, Math.min(AVERAGES.size(), MAX_SPRITES))];
        for (int i = 0; i < colors.length; i++) {
            float[] average = AVERAGES.get(i);
            if (average == null || average[3] <= 0.0F) {
                // Unknown on the CPU side (callers fall back); the GPU row
                // still writes the white placeholder for tint normalisation.
                colors[i] = 0;
                continue;
            }
            colors[i] = 0xFF000000
                    | clamp255(average[0]) << 16
                    | clamp255(average[1]) << 8
                    | clamp255(average[2]);
        }
        averageColorsArgb = colors;
        if (colors.length > 0) {
            averageGeneration = 1;
        }
    }

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }

    /**
     * Resolves one sprite row.  The top variant prefers the baked model's
     * up-face quad (vanilla's grass block would otherwise contribute its
     * dirt particle to every predicted hilltop); the side variant takes the
     * particle icon, which matches the block's wall texture for columnar
     * and composite models.
     */
    private static int resolve(int blockId, int face) {
        Block block = BuiltInRegistries.BLOCK.byId(blockId);
        return block == null ? FLAT : resolve(block.defaultBlockState(), face);
    }

    private static int resolve(net.minecraft.world.level.block.state.BlockState state, int face) {
        try {
            if (state.isAir()) {
                return FLAT;
            }
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite sprite = null;
            net.minecraft.core.Direction direction = switch (face) {
                case 0 -> net.minecraft.core.Direction.UP;
                case 1 -> net.minecraft.core.Direction.NORTH;
                case 2 -> net.minecraft.core.Direction.SOUTH;
                case 3 -> net.minecraft.core.Direction.WEST;
                case 4 -> net.minecraft.core.Direction.EAST;
                default -> null;
            };
            if (direction != null) {
                var quads = model.getQuads(state, direction,
                        net.minecraft.util.RandomSource.create(42L));
                if (quads != null && !quads.isEmpty()
                        && quads.get(0).getSprite() != null) {
                    sprite = quads.get(0).getSprite();
                }
                // Some custom models put all geometry in the unculled list;
                // use it before falling back to the particle icon, exactly as
                // the does for an empty directional layer.
                if (sprite == null) {
                    var unculled = model.getQuads(state, null,
                            net.minecraft.util.RandomSource.create(42L));
                    if (unculled != null && !unculled.isEmpty()
                            && unculled.get(0).getSprite() != null) {
                        sprite = unculled.get(0).getSprite();
                    }
                }
            }
            if (sprite == null) {
                sprite = model.getParticleIcon();
            }
            if (sprite == null) {
                return FLAT;
            }
            return registerSprite(sprite);
        } catch (Throwable failure) {
            // A custom model can require a live block entity. Its fallback
            // must not turn the entire world's already valid atlas into flat color.
            if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging)
                dev.xantha.vss.common.VSSLogger.debug("VSS surface model unavailable: " + state + ": " + failure);
            return FLAT;
        }
    }

    /** Called under the table lock; six identical block faces share one row. */
    static synchronized int registerSprite(TextureAtlasSprite sprite) {
        ResourceLocation name = sprite.contents().name();
        Integer existing = INDEX_BY_SPRITE.get(name);
        if (existing != null) return existing;
        if (RECTS.size() >= MAX_SPRITES) return FLAT;
        RECTS.add(new float[]{sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()});
        AVERAGES.add(averageOf(sprite));
        CUTOUTS.add(hasTransparency(sprite));
        MODEL_UVS.add(new float[0]);
        int index = RECTS.size();
        INDEX_BY_SPRITE.put(name, index);
        dirty = true;
        return index;
    }

    /** Preserve each baked face's UV rectangle and rotation, not its particle icon. */
    static synchronized int registerModelFace(net.minecraft.client.renderer.block.model.BakedQuad quad, int blockId) {
        TextureAtlasSprite sprite = quad.getSprite();
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        List<Float> uv = new ArrayList<>(8);
        for (int corner = 0; corner < 4; corner++) {
            uv.add((Float.intBitsToFloat(vertices[corner * stride + 4]) - sprite.getU0()) / (sprite.getU1() - sprite.getU0()));
            uv.add((Float.intBitsToFloat(vertices[corner * stride + 5]) - sprite.getV0()) / (sprite.getV1() - sprite.getV0()));
        }
        ModelUvKey key = new ModelUvKey(sprite.contents().name(), List.copyOf(uv), quad.isShade(), blockId);
        Integer existing = MODEL_ROWS.get(key);
        if (existing != null) return existing;
        if (RECTS.size() >= MAX_SPRITES) return FLAT;
        RECTS.add(new float[]{sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()});
        AVERAGES.add(averageOf(sprite));
        CUTOUTS.add(hasTransparency(sprite));
        float[] coords = new float[8];
        for (int i = 0; i < 8; i++) coords[i] = uv.get(i);
        MODEL_UVS.add(coords);
        int row = RECTS.size();
        MODEL_ROWS.put(key, row);
        MODEL_BLOCKS.put(row, blockId);
        byte[] flags = modelFlags.clone();
        flags[row] = (byte) (quad.isShade() ? 1 : 3);
        modelFlags = flags;
        dirty = true;
        return row;
    }

    static boolean modelFace(int row) {
        return row > 0 && row < FLAT && (modelFlags[row] & 1) != 0;
    }

    static boolean modelShade(int row) { return row <= 0 || row >= FLAT || (modelFlags[row] & 2) == 0; }

    static int modelColor(int row, int tint) {
        int average = averageForSprite(row);
        if (average == 0) return 0xFF65934A;
        int r = ((average >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((average >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (average & 255) * (tint & 255) / 255;
        return (row == FLAT ? 0xFF000000 : row << 24) | r << 16 | g << 8 | b;
    }

    static synchronized float[] modelUvs(int row) { return MODEL_UVS.get(row - 1).clone(); }

    /**
     * Returns the rect-table texture (row 0 zeroed, row i = sprite i),
     * rebuilding it when entries changed. Returns -1 when the table broke, in
     * which case callers must keep detail tiles on the flat path.
     */
    static int texture() {
        if (broken) {
            return -1;
        }
        if (textureId != -1 && !dirty) {
            return textureId;
        }
        // Newly published meshes already refer to newly registered rows. Upload
        // them in the same frame; a half-second delay flashes flat materials.
        prepare();
        try {
            if (textureId == -1) {
                textureId = com.mojang.blaze3d.platform.TextureUtil.generateTextureId();
            }
            // Workers may append rows while this rebuild runs; snapshot the
            // tables under the resolve lock so the loops below never walk a
            // list that changes size underneath them.
            List<float[]> rects;
            List<float[]> averages;
            List<float[]> modelUvs;
            synchronized (VssLodSpriteTable.class) {
                rects = new ArrayList<>(RECTS);
                averages = new ArrayList<>(AVERAGES);
                modelUvs = new ArrayList<>(MODEL_UVS);
                publishAverageColors();
                dirty = false;
            }
            int width = Math.max(1, rects.size() + 1);
            // Row 0: atlas rectangles; row 1: alpha-weighted averages;
            // rows 2/3: the four original UV corners of baked model faces.
            // Index 0 stays zero and is the flat-colour sentinel.
            FloatBuffer data = MemoryUtil.memAllocFloat(width * 4 * 4);
            for (int i = 0; i < width * 4 * 4; i++) {
                data.put(0.0F);
            }
            for (int i = 0; i < rects.size(); i++) {
                float[] rect = rects.get(i);
                data.put((i + 1) * 4, rect[0]);
                data.put((i + 1) * 4 + 1, rect[1]);
                data.put((i + 1) * 4 + 2, rect[2]);
                data.put((i + 1) * 4 + 3, rect[3]);
            }
            for (int i = 0; i < averages.size(); i++) {
                float[] average = averages.get(i);
                data.put(width * 4 + (i + 1) * 4, average[0]);
                data.put(width * 4 + (i + 1) * 4 + 1, average[1]);
                data.put(width * 4 + (i + 1) * 4 + 2, average[2]);
                data.put(width * 4 + (i + 1) * 4 + 3, average[3]);
            }
            for (int i = 0; i < modelUvs.size(); i++) for (int c = 0; c < modelUvs.get(i).length; c++) {
                data.put((2 + c / 4) * width * 4 + (i + 1) * 4 + c % 4, modelUvs.get(i)[c]);
            }
            data.flip();
            GlStateManager._bindTexture(textureId);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, 4, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, data);
            GlStateManager._bindTexture(0);
            MemoryUtil.memFree(data);
            return textureId;
        } catch (Throwable failure) {
            broken = true;
            dev.xantha.vss.common.VSSLogger.warn(
                    "VSS prediction sprite table disabled after an upload error", failure);
            return -1;
        }
    }

    /**
     * Alpha-weighted average colour of one sprite's original image, into
     * linear 0-1 floats (rgb) plus the mean alpha.  The packed-quad LOD approach's
     * LodSpriteAverage: read the CPU-resident NativeImage through NeoForge's
     * SpriteContents API instead of reading the whole atlas back from the GPU --
     * the old full-atlas glGetTexImage hitched the render thread on every
     * table rebuild and the mipped, padded atlas made the value inexact.
     */
    private static float[] averageOf(TextureAtlasSprite sprite) {
        try {
            NativeImage image = sprite.contents().getOriginalImage();
            if (image == null) {
                return new float[]{1.0F, 1.0F, 1.0F, 0.0F};
            }
            int width = Math.min(sprite.contents().width(), image.getWidth());
            int height = Math.min(sprite.contents().height(), image.getHeight());
            double red = 0.0D;
            double green = 0.0D;
            double blue = 0.0D;
            double weight = 0.0D;
            long alphaSum = 0L;
            long count = 0L;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int alpha = FastColor.ABGR32.alpha(pixel);
                    alphaSum += alpha;
                    count++;
                    if (alpha == 0) {
                        continue;
                    }
                    red += (double) (FastColor.ABGR32.red(pixel) * alpha);
                    green += (double) (FastColor.ABGR32.green(pixel) * alpha);
                    blue += (double) (FastColor.ABGR32.blue(pixel) * alpha);
                    weight += (double) alpha;
                }
            }
            if (weight <= 0.0D || count == 0L) {
                return new float[]{1.0F, 1.0F, 1.0F, 0.0F};
            }
            return new float[]{(float) (red / weight / 255.0D),
                    (float) (green / weight / 255.0D),
                    (float) (blue / weight / 255.0D),
                    (float) ((double) alphaSum / (double) count / 255.0D)};
        } catch (Throwable failure) {
            if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) {
                dev.xantha.vss.common.VSSLogger.debug(
                        "VSS sprite average unavailable; using material fallback: " + failure.getMessage());
            }
            return new float[]{1.0F, 1.0F, 1.0F, 0.0F};
        }
    }

    private static boolean hasTransparency(TextureAtlasSprite sprite) {
        try {
            var contents = sprite.contents();
            for (int y = 0; y < contents.height(); y++) {
                for (int x = 0; x < contents.width(); x++) {
                    if (contents.isTransparent(0, x, y)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // An unavailable image is treated as opaque. The flat average
            // path remains valid and does not create an alpha discard hole.
        }
        return false;
    }

    static void close() {
        if (textureId != -1) {
            com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(textureId);
            textureId = -1;
        }
        synchronized (VssLodSpriteTable.class) {
            INDEX_BY_BLOCK.clear();
            SIDE_INDEX_BY_BLOCK.clear();
            SIDE_INDEX_BY_FACE_BLOCK.clear();
            INDEX_BY_STATE_FACE.clear();
            INDEX_BY_SPRITE.clear();
            java.util.Arrays.fill(FLUID_SPRITES, 0);
            RECTS.clear();
            AVERAGES.clear();
            CUTOUTS.clear();
            MODEL_UVS.clear();
            MODEL_ROWS.clear();
            MODEL_BLOCKS.clear();
            modelFlags = new byte[256];
        }
        averageColorsArgb = null;
        seeded = false;
        runtimeReady = false;
        atlasToken = null;
        dirty = true;
        broken = false;
    }
}
