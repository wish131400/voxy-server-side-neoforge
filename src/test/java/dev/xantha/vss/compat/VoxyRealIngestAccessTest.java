package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class VoxyRealIngestAccessTest {
    @TempDir Path directory;
    public static final class Engine {
        public final Object storage=new Object();
        final Map<Long,Section> sections=new HashMap<>();
        final List<Long> saves=new ArrayList<>();
        public Section acquire(long key) { return sections.computeIfAbsent(key,Section::new); }
        public boolean saveSection(Section section,boolean nonblocking,boolean acquired) {
            assertTrue(nonblocking);assertFalse(acquired);assertTrue(section.dirty);
            saves.add(section.key);return true;
        }
    }
    public static final class Section {
        public final long key;
        boolean dirty;int releases;
        Section(long key) { this.key=key; }
        public void markDirty() { dirty=true; }
        public void release() { releases++; }
    }
    @BeforeEach void reset() { VoxyIngestTracker.resetWorld(); }

    @Test void unchangedAirStillQueuesProofForAllNativeLevelsOnceWithBalancedOwnership() {
        Engine engine=new Engine();VoxyRealCoveragePersistence.register(engine.storage,directory,"save-real-air");
        int changed=VoxyRealIngestAccess.committed(engine,-3,5,-7);
        assertEquals(31,changed);
        VoxyRealIngestAccess.save(engine,-3,5,-7,changed);
        assertEquals(5,engine.saves.size());
        for(int lod=0;lod<=4;lod++) {
            long key=VoxySectionPosition.key(lod,-3>>(lod+1),5>>(lod+1),-7>>(lod+1));
            assertEquals(key,engine.saves.get(lod));assertEquals(1,engine.sections.get(key).releases);
        }
        assertEquals(0,VoxyRealIngestAccess.committed(engine,-3,5,-7));
        assertEquals(5,engine.saves.size());
    }

    @Test void wholeColumnOptimizationRequiresEveryActualHeightAndSurvivesRendererReset() {
        VoxyRealCoverage coverage=VoxyRealCoverage.of(new Object());coverage.verticalRange(-4,24);
        for(int y=-4;y<19;y++)coverage.mark(-2,y,3);
        assertFalse(coverage.contains(-2,3));coverage.mark(-2,19,3);
        assertTrue(coverage.contains(-2,3));assertFalse(coverage.contains(-1,3));
        VoxyIngestTracker.reset();assertTrue(coverage.contains(-2,3));
        VoxyIngestTracker.resetWorld();assertFalse(coverage.contains(-2,3));
    }
}
