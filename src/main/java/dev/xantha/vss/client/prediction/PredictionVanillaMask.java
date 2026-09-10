package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * GPU-readable snapshot of the client sections that have finished compiling.
 *
 * <p>This is a second occluder beside the imported framebuffer
 * depth.  The depth buffer only describes the pixels that happened to be
 * visible this frame; this mask also prevents a prediction wall or canopy
 * from being drawn through a compiled section after the camera turns.</p>
 */
final class PredictionVanillaMask {
    private static final int SECTION_BLOCKS = 16;
    private static final long REFRESH_NANOS = 125_000_000L;

    // GL objects must be created lazily: PredictionRenderer is also loaded by
    // headless protocol/unit tests that do not have a render context.
    private int texture = -1;
    private ByteBuffer upload = ByteBuffer.allocateDirect(1);
    private byte[] states = new byte[0];
    private int sizeXZ;
    private int sizeY;
    private int originChunkX;
    private int originChunkZ;
    private int originSectionY;
    private long lastRefreshNanos;
    private int lastCameraChunkX = Integer.MIN_VALUE;
    private int lastCameraChunkZ = Integer.MIN_VALUE;
    private int lastRadius = -1;
    private boolean dirty = true;
    private java.util.List<PredictionRealBoundarySeams.Edge> groundEdges = java.util.List.of();

    java.util.List<PredictionRealBoundarySeams.Edge> groundEdges() { return groundEdges; }

    /** Refreshes section state at most eight times per second unless reanchored. */
    void update(Minecraft minecraft, Vec3 camera) {
        ClientLevel level = minecraft.level;
        if (level == null || camera == null) {
            invalidate();
            return;
        }
        int radius = Math.max(1, minecraft.options.getEffectiveRenderDistance());
        int cameraChunkX = SectionPos.blockToSectionCoord(camera.x);
        int cameraChunkZ = SectionPos.blockToSectionCoord(camera.z);
        int nextOriginX = cameraChunkX - radius;
        int nextOriginZ = cameraChunkZ - radius;
        int nextSizeY = Math.max(1, level.getSectionsCount());
        int nextOriginY = level.getMinSection();
        boolean reanchored = sizeXZ != radius * 2 + 1
                || sizeY != nextSizeY
                || originChunkX != nextOriginX
                || originChunkZ != nextOriginZ
                || originSectionY != nextOriginY
                || cameraChunkX != lastCameraChunkX
                || cameraChunkZ != lastCameraChunkZ
                || radius != lastRadius;
        long now = System.nanoTime();
        if (!reanchored && now - lastRefreshNanos < REFRESH_NANOS) {
            return;
        }
        reanchor(radius * 2 + 1, nextSizeY, nextOriginX, nextOriginZ, nextOriginY);
        LevelRenderer renderer = minecraft.levelRenderer;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        LevelChunk[] compiledGround = new LevelChunk[sizeXZ * sizeXZ];
        for (int z = 0; z < sizeXZ; z++) {
            for (int x = 0; x < sizeXZ; x++) {
                int chunkX = originChunkX + x;
                int chunkZ = originChunkZ + z;
                LevelChunk chunk = level.getChunkSource().getChunk(
                        chunkX, chunkZ, ChunkStatus.FULL, false);
                boolean surfaceCompiled = chunk != null
                        && surfaceCompiled(renderer, chunk, chunkX, chunkZ, position);
                if (surfaceCompiled) compiledGround[z * sizeXZ + x] = chunk;
                for (int y = 0; y < sizeY; y++) {
                    boolean compiled = surfaceCompiled;
                    if (!compiled && chunk != null) {
                        position.set(SectionPos.sectionToBlockCoord(chunkX),
                                SectionPos.sectionToBlockCoord(originSectionY + y),
                                SectionPos.sectionToBlockCoord(chunkZ));
                        compiled = renderer.isSectionCompiled(position);
                    }
                    states[index(x, y, z)] = (byte) (compiled ? 1 : 0);
                }
            }
        }
        refreshGroundEdges(compiledGround, position);
        lastCameraChunkX = cameraChunkX;
        lastCameraChunkZ = cameraChunkZ;
        lastRadius = radius;
        lastRefreshNanos = now;
        dirty = true;
    }

    private void refreshGroundEdges(LevelChunk[] compiled, BlockPos.MutableBlockPos position) {
        var edges = new java.util.ArrayList<PredictionRealBoundarySeams.Edge>();
        for (int z = 0; z < sizeXZ; z++) for (int x = 0; x < sizeXZ; x++) {
            LevelChunk chunk = compiled[z * sizeXZ + x];
            if (chunk == null) continue;
            for (int direction = 0; direction < 4; direction++) {
                int nx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
                int nz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                int ax = x + nx, az = z + nz;
                if (ax >= 0 && az >= 0 && ax < sizeXZ && az < sizeXZ
                        && compiled[az * sizeXZ + ax] != null) continue;
                for (int along = 0; along < 16; along++) {
                    int wx = (originChunkX + x) * 16 + (nx == 0 ? along : nx > 0 ? 15 : 0);
                    int wz = (originChunkZ + z) * 16 + (nz == 0 ? along : nz > 0 ? 15 : 0);
                    var ground = boundaryGround(chunk, wx, wz, position);
                    if (ground != null) edges.add(new PredictionRealBoundarySeams.Edge(wx, wz, nx, nz, ground));
                }
            }
        }
        if (!groundEdges.equals(edges)) groundEdges = java.util.List.copyOf(edges);
    }

    private static ClientColumnSample boundaryGround(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        int water = top + 1, fluid = 0;
        // Read only perimeter blocks. No noise sampling, chunk generation or cave scan.
        for (int y = top; y >= Math.max(chunk.getMinBuildHeight(), top - 31); y--) {
            var state = chunk.getBlockState(pos.set(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                if (fluid == 0) fluid = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 2 : 1;
                continue;
            }
            if (ClientCaptureExtractor.isGround(state)) {
                var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
                int block = registry.getId(state.getBlock());
                var below = chunk.getBlockState(pos.set(x, y - 1, z));
                int under = ClientCaptureExtractor.isGround(below) ? registry.getId(below.getBlock()) : block;
                var deep = chunk.getBlockState(pos.set(x, y - 7, z));
                int deepId = ClientCaptureExtractor.isGround(deep) ? registry.getId(deep.getBlock()) : under;
                return new ClientColumnSample(y + 1, fluid == 0 ? y + 1 : water, 0, block,
                        0, 0, 0, 0, fluid, ClientColumnSample.FLAG_CAPTURED, 0, under, deepId,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            if (state.canOcclude()) return null;
        }
        return null;
    }

    /** Clears CPU state after a world change without issuing GL calls off-thread. */
    void invalidate() {
        sizeXZ = 0;
        sizeY = 0;
        states = new byte[0];
        groundEdges = java.util.List.of();
        lastCameraChunkX = Integer.MIN_VALUE;
        lastCameraChunkZ = Integer.MIN_VALUE;
        lastRadius = -1;
        lastRefreshNanos = 0L;
        dirty = true;
    }

    void bind(int unit, Vec3 camera, int originUniform, int sizeUniform) {
        if (texture == -1) {
            texture = TextureUtil.generateTextureId();
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        refresh();
        org.lwjgl.opengl.GL20.glUniform3f(originUniform,
                (float) (originChunkX * SECTION_BLOCKS - camera.x),
                (float) (originSectionY * SECTION_BLOCKS - camera.y),
                (float) (originChunkZ * SECTION_BLOCKS - camera.z));
        org.lwjgl.opengl.GL20.glUniform3i(sizeUniform, sizeXZ, sizeY, sizeXZ);
    }

    float renderDistanceBlocks() {
        return Math.max(0, lastRadius) * SECTION_BLOCKS;
    }

    static void unbind(int unit) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private static boolean surfaceCompiled(LevelRenderer renderer,
                                             LevelChunk chunk, int chunkX, int chunkZ,
                                             BlockPos.MutableBlockPos position) {
        var sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            if (sections[index].hasOnlyAir()) continue;
            int sectionY = chunk.getSectionYFromSectionIndex(index);
            position.set(SectionPos.sectionToBlockCoord(chunkX),
                    SectionPos.sectionToBlockCoord(sectionY),
                    SectionPos.sectionToBlockCoord(chunkZ));
            if (!renderer.isSectionCompiled(position)) {
                return false;
            }
        }
        return true;
    }

    private int index(int x, int y, int z) {
        return (z * sizeY + y) * sizeXZ + x;
    }

    private void reanchor(int nextSizeXZ, int nextSizeY, int nextOriginX,
                          int nextOriginZ, int nextOriginY) {
        if (nextSizeXZ == sizeXZ && nextSizeY == sizeY
                && nextOriginX == originChunkX && nextOriginZ == originChunkZ
                && nextOriginY == originSectionY) {
            java.util.Arrays.fill(states, (byte) 0);
            return;
        }
        sizeXZ = nextSizeXZ;
        sizeY = nextSizeY;
        originChunkX = nextOriginX;
        originChunkZ = nextOriginZ;
        originSectionY = nextOriginY;
        states = new byte[sizeXZ * sizeY * sizeXZ];
        int bytes = Math.max(1, states.length);
        if (upload.capacity() < bytes) {
            ByteBuffer replacement = ByteBuffer.allocateDirect(bytes);
            upload = replacement;
        }
    }

    private void refresh() {
        if (!dirty) {
            return;
        }
        dirty = false;
        upload.clear();
        for (byte state : states) {
            upload.put((byte) (state == 0 ? 0 : 255));
        }
        upload.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8,
                Math.max(1, sizeXZ), Math.max(1, sizeY), Math.max(1, sizeXZ),
                0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
                states.length == 0 ? null : upload);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }
}
