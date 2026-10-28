package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;

class VoxyRealAirAuthorityTest {
    private final Object engine = new Object();
    private Object index;

    @BeforeAll static void paths() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createTempDirectory("vss-real-air"));
    }
    @BeforeEach void setup() throws Exception {
        VoxyIngestTracker.reset();
        VoxyCompat.onDisconnect();
        Class<?> type = Class.forName(VoxyCompat.class.getName() + "$LocalSectionIndex");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        index = constructor.newInstance(); indexes().put(engine, index);
    }
    @AfterEach void cleanup() { VoxyCompat.onDisconnect(); VoxyIngestTracker.reset(); }

    @Test void firstRealColumnMustFinishBeforeItsAirCanReplacePrediction() throws Exception {
        var first = VoxyIngestTracker.begin(new Object(), -2, 3);
        var last = VoxyIngestTracker.begin(new Object(), -2, 3);
        accepted(-2, 3);
        assertFalse(VoxyCompat.confirmedPredictionColumn(engine, -2, 3));
        VoxyIngestTracker.complete(first);
        assertFalse(VoxyCompat.confirmedPredictionColumns(engine).contains(-2, 3));
        VoxyIngestTracker.complete(last);
        assertTrue(VoxyCompat.confirmedPredictionColumn(engine, -2, 3));
        assertFalse(VoxyCompat.confirmedPredictionColumn(engine, -1, 3));
    }

    @Test void aLaterSectionUpdateCannotFillAConfirmedCaveWithPredictionAtAnyNativeLod() throws Exception {
        for (int lod = 0; lod <= 4; lod++) {
            int cx = -(2 << lod), cz = -(2 << lod), group = 16 >> lod;
            accepted(cx, cz);
            var update = VoxyIngestTracker.begin(new Object(), cx, cz);
            try {
                long[] predicted = new long[32768], real = new long[32768];
                Arrays.fill(predicted, 1L << 27); Arrays.fill(real, 15L << 56);
                real[31 << 10] = 2L << 27; // Real roof, with a fully empty cave section below it.
                VoxyPredictionBridge.mergeReal(predicted, real, lod, -1, -1, -1,
                        VoxyCompat.confirmedPredictionColumns(engine));
                assertEquals(15L << 56, predicted[0], "Confirmed underground air at LOD " + lod);
                assertEquals(1L << 27, predicted[group], "Adjacent unknown column at LOD " + lod);
                assertEquals(2L << 27, predicted[31 << 10], "Updated real geometry at LOD " + lod);
            } finally { VoxyIngestTracker.complete(update); }
        }
    }

    @Test void completionBeforeARenderContextExistsStillSurvivesTheNextUpdate() throws Exception {
        var first = VoxyIngestTracker.begin(new Object(), 4, -5);
        accepted(4, -5);
        VoxyIngestTracker.complete(first); // No renderer or authority query has run yet.
        var update = VoxyIngestTracker.begin(new Object(), 4, -5);
        try {
            assertTrue(VoxyCompat.confirmedPredictionColumn(engine, 4, -5));
            assertTrue(VoxyCompat.confirmedPredictionColumns(engine).contains(4, -5));
        } finally { VoxyIngestTracker.complete(update); }
    }

    @Test void aConfirmedEmptyUndergroundSectionStaysEmptyDuringUpdatesAtAnotherHeight() throws Exception {
        for (int lod = 0; lod <= 4; lod++) {
            int cx = 2 << lod, cz = -(2 << lod), group = 16 >> lod;
            accepted(cx, cz);
            var update = VoxyIngestTracker.begin(new Object(), cx, cz);
            try {
                long[] predicted = new long[32768]; Arrays.fill(predicted, 1L << 27);
                VoxyPredictionBridge.mergeReal(predicted, null, lod, 1, -1, -1,
                        VoxyCompat.confirmedPredictionColumns(engine));
                for (int y = 0; y < 32; y++) for (int z = 0; z < group; z++) for (int x = 0; x < group; x++)
                    assertEquals(15L << 56, predicted[y << 10 | z << 5 | x], "Empty real section at LOD " + lod);
                assertEquals(1L << 27, predicted[group], "Unknown neighbor remains filled at LOD " + lod);
            } finally { VoxyIngestTracker.complete(update); }
        }
    }

    @Test void finishedIngestWithoutAnAcceptedAuthoritativeColumnCannotClaimMissingAir() {
        var update = VoxyIngestTracker.begin(new Object(), 4, -5);
        VoxyIngestTracker.complete(update);
        assertFalse(VoxyCompat.confirmedPredictionColumn(engine, 4, -5));
    }

    @Test void aCompletedColumnCannotTransferAuthorityToAnotherWorldOrSession() throws Exception {
        accepted(-1, -1);
        assertTrue(VoxyCompat.confirmedPredictionColumn(engine, -1, -1));
        assertFalse(VoxyCompat.confirmedPredictionColumn(new Object(), -1, -1));
        var oldAuthority = VoxyCompat.confirmedPredictionColumns(engine);
        VoxyCompat.onDisconnect();
        assertFalse(VoxyCompat.confirmedPredictionColumn(engine, -1, -1));
        assertFalse(oldAuthority.contains(-1, -1));
    }

    private void accepted(int x, int z) throws Exception {
        var mark = index.getClass().getDeclaredMethod("markConfirmed", int.class, int.class);
        mark.setAccessible(true); mark.invoke(index, x, z);
        VoxyPredictionBridge.realColumnSettled(x, z);
    }
    @SuppressWarnings("unchecked") private static Map<Object, Object> indexes() throws Exception {
        Field field = VoxyCompat.class.getDeclaredField("localIndexes"); field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
