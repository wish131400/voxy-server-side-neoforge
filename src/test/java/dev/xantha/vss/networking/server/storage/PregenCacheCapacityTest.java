package dev.xantha.vss.networking.server.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PregenCacheCapacityTest {
    @TempDir Path root;
    @Test void coldCacheEstimateRejectsOversizedJobAtTheBoundary() {
        var plan = new PregenCacheCapacity(0, 0);
        assertEquals(655360, plan.capacity(10240, 2500000));
        assertTrue(plan.fits(655360, 10240, 2500000));
        assertFalse(plan.fits(655361, 10240, 2500000));
    }
    @Test void measuredLargerColumnsGetHeadroomAndEntryLimitStillApplies() {
        var plan = new PregenCacheCapacity(100 * 32768L, 100);
        assertEquals(40960, plan.estimatedColumnBytes());
        assertEquals(262144, plan.capacity(10240, 2500000));
        assertEquals(250000, plan.capacity(10240, 250000));
    }
    @Test void capacityScanIgnoresExpiredColumnsAndNonColumnFiles() throws Exception {
        Files.write(root.resolve("fresh.vcl"), new byte[20000]);
        Path old = Files.write(root.resolve("old.vcl"), new byte[100000]);
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));
        Files.write(root.resolve("index.vci"), new byte[9000]);
        var plan = PregenCacheCapacity.inspect(root, 7);
        assertEquals(1, plan.sampleColumns()); assertEquals(20000, plan.sampleBytes());
        assertEquals(25000, plan.estimatedColumnBytes());
    }
}
