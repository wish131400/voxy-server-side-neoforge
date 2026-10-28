package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoxyRenderAccessTest {
    public static class IntegerConfig { public int sectionRenderDistance = 16; }
    public static class FloatConfig { public float sectionRenderDistance = 16.5f; }
    public static class NeighborWorld {
        int acquired, lookedUp;
        final Object section = new Object();
        public Object acquire(int l, int x, int y, int z) { acquired++; return section; }
        public Object acquireIfExists(int l, int x, int y, int z) { lookedUp++; return null; }
    }
    public static class IntegerRenderer {
        int distance;
        public void setRenderDistance(int value) { distance = value; }
    }
    public static class FloatRenderer {
        float distance;
        public void setRenderDistance(float value) { distance = value; }
    }
    public static class LegacySection {
        final long[] data = new long[32768];
        public long[] _unsafeGetRawDataArray() { return data; }
    }
    public static class UniformSection {
        long[] data;
        int allocations;
        public long[] _rawOrNull() { return data; }
        public long getUniformValue() { return 1L << 27; }
        public long[] materialize() {
            if (data == null) { allocations++; data = new long[32768]; java.util.Arrays.fill(data, getUniformValue()); }
            return data;
        }
    }
    public static class LegacyEngine {
        public static long getWorldSectionId(int l, int x, int y, int z) {
            return (long) l << 60 | (long) (y & 255) << 52 | (long) (z & 0xffffff) << 28 | (long) (x & 0xffffff) << 4;
        }
    }
    public static class ExtendedEngine {
        public static long getWorldSectionId(int l, int x, int y, int z) {
            if (y >= -128 && y <= 127) return LegacyEngine.getWorldSectionId(l, x, y, z);
            return (long) l << 60 | (long) (y & 4095) << 48 | (long) (z & 0xffffff) << 24 | (long) (x & 0x7fffff) << 1 | 1;
        }
    }
    @AfterEach void restoreLegacyKeys() throws Exception { VoxySectionPosition.bind(LegacyEngine.class); }

    @Test void serverHorizonExpandsVoxyWithoutPredictionAndDisconnectRestoresIt() throws Exception {
        float extended = VoxyRenderAccess.renderDistance(64, 4096, 0);
        assertEquals(128f, extended, "4096 VSS chunks must exceed Voxy's configured 2048 chunks");
        var old = new IntegerRenderer(); var current = new FloatRenderer();
        VoxyRenderAccess.Distance.bind(old.getClass()).set(old, extended);
        VoxyRenderAccess.Distance.bind(current.getClass()).set(current, extended);
        assertEquals(128, old.distance); assertEquals(128f, current.distance);
        assertEquals(64f, VoxyRenderAccess.renderDistance(64, 0, 0));
        assertEquals(128f, VoxyRenderAccess.renderDistance(64, 0, 65536));
        assertEquals(160f, VoxyRenderAccess.renderDistance(160, 4096, 65536));
        assertEquals(129f, VoxyRenderAccess.renderDistance(64, 4097, 0));
        assertEquals(48000f, VoxyRenderAccess.farPlane(48000f, 0));
        assertEquals(48000f, VoxyRenderAccess.farPlane(48000f, 2048 / 2));
        assertTrue(VoxyRenderAccess.farPlane(48000f, 4096) > 65536f * Math.sqrt(2),
                "Loaded corners of the 4096-chunk horizon must survive projection clipping");
        assertEquals(200000f, VoxyRenderAccess.farPlane(200000f, 4096));
    }

    @Test void distanceUsesTheActualPrimitiveSignature() throws Exception {
        var old = new IntegerRenderer();
        VoxyRenderAccess.Distance.bind(old.getClass()).set(old, 8.25f);
        assertEquals(9, old.distance);
        var current = new FloatRenderer();
        VoxyRenderAccess.Distance.bind(current.getClass()).set(current, 8.25f);
        assertEquals(8.25f, current.distance);
    }
    @Test void configDistanceGetterWidensTheLegacyInteger() throws Throwable {
        Object old = new IntegerConfig(), current = new FloatConfig();
        assertEquals(16f, (float) VoxyRenderAccess.distanceField(old.getClass()).invokeExact(old));
        assertEquals(16.5f, (float) VoxyRenderAccess.distanceField(current.getClass()).invokeExact(current));
    }
    @Test void uniformRealDataOverridesPredictionWithoutAllocatingAnArray() throws Exception {
        var real = new UniformSection();
        var access = new VoxyRenderAccess.Sections(real.getClass());
        var predicted = new long[32768];
        VoxyPredictionBridge.mergeRealVoxels(predicted, access.read(real), 0, 0, 0, 0, (x, z) -> false);
        assertEquals(0, real.allocations);
        for (long value : predicted) assertEquals(real.getUniformValue(), value);
        var writable = access.writable(real);
        writable[15] = 42;
        assertEquals(42, access.read(real).get(15));
        assertEquals(1, real.allocations);
    }
    @Test void legacyReadsAndWritesKeepTheNativeArray() throws Exception {
        var real = new LegacySection();
        var access = new VoxyRenderAccess.Sections(real.getClass());
        access.writable(real)[32767] = 99;
        assertSame(real.data, access.read(real).data());
        assertEquals(99, access.read(real).get(32767));
    }
    @Test void neighborVariantsRetainNativeAllocationAndNullSemantics() {
        var world = new NeighborWorld();
        assertSame(world.section, VoxyPredictionBridge.neighborSection(world, 0, -4, 0, 8));
        assertNull(VoxyPredictionBridge.neighborSectionIfExists(world, 0, -4, 0, 8));
        assertEquals(1, world.acquired);
        assertEquals(1, world.lookedUp);
    }
    @Test void extendedHeightDoesNotAliasLegacyNodesOrPendingWork() throws Exception {
        VoxySectionPosition.bind(ExtendedEngine.class);
        var nodes = new StrictVoxyNodeIndex();
        long high = StrictVoxyNodeIndex.key(0, -4, 300, -8);
        assertEquals(-4, StrictVoxyNodeIndex.x(high));
        assertEquals(300, StrictVoxyNodeIndex.y(high));
        assertEquals(-8, StrictVoxyNodeIndex.z(high));
        nodes.update(7, high, 0);
        assertTrue(nodes.coversFinest(-8, 600, -16));
        assertFalse(nodes.coversFinest(-8, 88, -16));
        assertTrue(nodes.coversBox(-8, 600, -16, -7, 601, -15));
        var pipeline = new StrictVoxyPipeline();
        pipeline.queued(high);
        assertFalse(pipeline.idleRange(-8, 599, 601, -16));
        assertFalse(pipeline.idleBox(-9, 599, -17, -7, 601, -15));
        assertTrue(pipeline.idleBox(-9, 87, -17, -7, 89, -15));
        assertTrue(pipeline.idleRange(-8, 87, 89, -16));
    }
}
