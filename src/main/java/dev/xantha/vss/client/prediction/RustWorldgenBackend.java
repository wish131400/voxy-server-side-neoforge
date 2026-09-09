package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Independent ABI 2 for the source-built Rust backend. All numerical work runs
 * in Rust; Java supplies effective registries, colormaps and immutable inputs.
 *
 * <p>A surface region is before carvers/structures/decoration and
 * must not be published as a completed generated chunk. Query support before
 * sending a feature; unsupported Java/mod codecs are never silently skipped.
 *
 * <p>Buffers are direct, little endian, indexed from the buffer base. Pass a
 * slice when using a nonzero position. Counts are records, never byte counts.
 * World/result handles must be closed; a result retains its parent world.
 */
public final class RustWorldgenBackend {
    public static final int ABI = 2;
    private RustWorldgenBackend() { }
    public static void load(Path library) {
        System.load(library.toAbsolutePath().toString());
        if (abi() != ABI) throw new IllegalStateException("VSS worldgen ABI mismatch");
    }
    public static native int abi();
    /** In-place 36-byte compact FTF cells; validates before committing ordered tile filters. */
    public static native void freeTerraForgedFilters(ByteBuffer cells, String settings);
    public static native long create(long seed, long biomeZoomSeed, String document);
    public static native int close(long handle);
    /** Stops outstanding surface work at column boundaries before closing a sampler. */
    public static native void cancel(long world);
    /** Replaces both resource-pack colormaps atomically; terrain algorithms stay unchanged. */
    public static native void colormaps(long world,String document);
    public static native String describe(long handle);
    public static native String support(long world, String featureJson, int placedFeature);
    /** XYZ int32 input; one float64 density per point, at most 65,536 points. */
    public static native int density(long world, String routerNode, ByteBuffer xyz, ByteBuffer output, int count);
    /** XZ int32 input; record = four int32 (surface, floor, fluid, height), then height int32 state IDs. */
    public static native int columns(long world, ByteBuffer xz, ByteBuffer output, int count);
    /** 1..5 chunks per side; returns a native volume result, not a world handle. */
    public static native long surfaceRegion(long world, int minChunkX, int minChunkZ, int sideChunks);
    /** Copies a complete bounded neighbourhood snapshot; state IDs refer to describe(world). */
    public static native long createVolume(long world, int x, int y, int z,
                                          int width, int height, int depth, ByteBuffer blocks);
    /** Dense int32 state IDs in X,Z,Y order; state table and bounds are in describe(result). */
    public static native int readVolume(long result, ByteBuffer output);
    /** Explicit configured feature with supplied WorldgenRandom seed, on the result's current block context. */
    public static native String feature(long result, String featureJson, long randomSeed, int x, int y, int z);
    /** Global per-step feature order computed by Rust from ordered possible_biomes. */
    public static native String schedule(long world);
    /** Rust derives decoration/feature seeds, checks biome membership and applies modifiers lazily. */
    public static native String placedFeature(long result, String name, int chunkX, int chunkZ, int globalIndex, int step);
    /** Selects the 3x3 biome neighbourhood and atomically executes one fully supported decoration step. */
    public static native String decorateStep(long result, int chunkX, int chunkZ, int step);
    /** 256 X,Z records of ten int32: floor, fluid top/kind, flags, three material states, grass/leaf/water RGB. */
    public static native int surfaceColumns(long world, int chunkX, int chunkZ, ByteBuffer output);
    /** Sparse XZ int32 input, at most 64 records, same ten-int32 output as surfaceColumns. */
    public static native int surfacePoints(long world, ByteBuffer xz, ByteBuffer output, int count);
    /** Existing prediction surface context, 5x5 chunks, assembled in Rust. */
    public static native long surfaceProxy(long world, int centerChunkX, int centerChunkZ);
    /** External Java Random entropy for vanilla Collections.shuffle; not the terrain seed. */
    public static native void decorationEntropy(long result,long seed);
    /** Sparse XYZ/state-ID edits; validated atomically before any writes. */
    public static native int applyEdits(long result, ByteBuffer edits, int count);
    /** Drains committed native edits on a successful copy; a rejected buffer preserves them. */
    public static native int readEdits(long result, ByteBuffer output);
    /** Grass, foliage and water tints at a block position, before optional biome blending. */
    public static native String colors(long world, int x, int y, int z);
    /** Three RGB int32 values at the exact block position, using BiomeManager zoom. */
    public static native int tints(long world, int x, int y, int z, ByteBuffer output);
}
