package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import java.io.IOException;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionCityMeshCacheTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static LostCityPreview.Tile city() {
        int state = Block.getId(Blocks.GLASS.defaultBlockState());
        var model = new LostCityPreview.Model(new int[]{LostCityPreview.origin(0, 0, 0),
                LostCityPreview.extent(16, 12, 0, 1), state});
        var value = new LostCityPreview.Chunk(2, -20, true, state, state, 0, 0,
                List.of(new LostCityPreview.Placement(0, model)), model,
                List.of(new LostCityPreview.Overlay(-30, model, model, model, model)));
        var chunks = new ArrayList<>(Collections.nCopies(36, LostCityPreview.EMPTY));
        chunks.set(7, value); chunks.set(8, value);
        return new LostCityPreview.Tile(-1, -1, 6, chunks);
    }

    @Test void losslessModelsGroundAndUnderwaterInfrastructureWithSharedChunks() throws Exception {
        var tile = city();
        byte[] encoded = PredictionCityMeshCache.encode(tile);
        var restored = PredictionCityMeshCache.decode(encoded);
        assertEquals(tile, restored);
        assertSame(restored.chunks().get(7), restored.chunks().get(8));
        assertTrue(encoded.length < 4096, "small summaries must not duplicate all building models per sample");
        assertThrows(IOException.class, () -> PredictionCityMeshCache.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] changedRegistry = encoded.clone();
        // Five header integers, the first numeric state and its UTF length.
        // The fixture registry is deliberately minimal; do not assume glass's live ID.
        int offset = 26;
        changedRegistry[offset] = (byte) (changedRegistry[offset] == 'x' ? 'y' : 'x');
        assertThrows(IOException.class, () -> PredictionCityMeshCache.decode(changedRegistry));
    }

    @Test void unknownIsDifferentFromConfirmedEmptyIncludingGutterAndNegativeCoordinates() {
        var hints = new LostCityHints(Level.OVERWORLD, 17, true);
        var stored = city();
        assertFalse(hints.cacheSnapshot(0, 0, 64).complete());
        assertTrue(hints.agrees(stored, 0, 0, 64));
        assertFalse(hints.agrees(stored, 64, 0, 64));
        for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++)
            hints.accept(empty(x, z));
        var known = hints.cacheSnapshot(0, 0, 64);
        assertTrue(known.complete()); assertNull(known.buildings()); assertNotNull(known.tile());
        assertFalse(hints.agrees(stored, 0, 0, 64));
        assertFalse(PredictionCityMeshCache.Proof.of(stored).agrees(empty(0, 0)));
        assertTrue(PredictionCityMeshCache.Proof.of(stored).agrees(empty(-1, -1)));
        var disabled = new LostCityHints(Level.OVERWORLD, 17, false).cacheSnapshot(0, 0, 64);
        assertTrue(disabled.complete()); assertNull(disabled.tile());
    }

    @Test void finishedMeshRequiresRawIdentityAndAllAlreadyKnownCityInputs() throws Exception {
        var mesh = PredictionMeshCodecTest.fixture(); var city = city();
        byte[] raw = new byte[32], full = new byte[32]; full[0] = 8;
        byte[] base = PredictionMeshCodec.withCityBuildings(raw, city);
        byte[] bytes = PredictionMeshCodec.encode(mesh, full, base, true, true, raw, city);
        var unknown = new LostCityHints(Level.OVERWORLD, 17, true);
        var restored = PredictionMeshCodec.decodeCityBaseRecord(bytes, raw, raw, mesh.cellAxis(),
                stored -> unknown.agrees(stored, 0, 0, 64));
        assertNotNull(restored); assertTrue(restored.surfaceCompleted()); assertEquals(city, restored.cities());
        assertArrayEquals(mesh.gpuPayload().cacheWords(), restored.mesh().gpuPayload().cacheWords());
        assertNotNull(PredictionMeshCodec.decode(bytes, full, mesh.cellAxis()), "full identity path stays compatible");
        byte[] changed = raw.clone(); changed[0] = 1;
        assertNull(PredictionMeshCodec.decodeCityBaseRecord(bytes, changed, raw, mesh.cellAxis(), stored -> true));
        unknown.accept(empty(0, 0));
        assertNull(PredictionMeshCodec.decodeCityBaseRecord(bytes, raw, raw, mesh.cellAxis(),
                stored -> unknown.agrees(stored, 0, 0, 64)));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
        assertThrows(IOException.class, () -> PredictionMeshCodec.decodeCityBaseRecord(truncated, raw, raw, mesh.cellAxis(), stored -> true));
    }

    private static LostCityHintsS2CPayload empty(int x, int z) {
        return new LostCityHintsS2CPayload(Level.OVERWORLD.location(), x, z, 17, true,
                Collections.nCopies(64, LostCityPreview.EMPTY));
    }
}
