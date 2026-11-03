package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Alternating same-coordinate A/B of repeated horizontal inputs, excluding construction. */
@EnabledIfSystemProperty(named="vss.climateBenchOutput", matches=".+")
class PredictionClimateSpeedTest {
    private static volatile long sink;
    @Test void measure() throws Exception {
        DensityMemoBenchTest.bootstrap();
        var rows = new ArrayList<Object>();
        String previous = System.getProperty("vss.horizontalClimate");
        try (var context = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(),42)) {
            var source = context.randomStateContext().sampler();
            System.setProperty("vss.horizontalClimate","off");
            try (var baseline = new PredictionClimateSampler(source)) {
                System.setProperty("vss.horizontalClimate","on");
                try (var optimized = new PredictionClimateSampler(source)) {
                    baseline.sampler().sample(0,0,0); optimized.sampler().sample(0,0,0);
                    PredictionClimateSamplerTest.awaitCompiled(baseline); PredictionClimateSamplerTest.awaitCompiled(optimized);
                    for (int round = -8; round < 16; round++) {
                        long[] times = new long[2], hashes = new long[2];
                        for (int order = 0; order < 2; order++) {
                            int variant = (round & 1) ^ order;
                            Climate.Sampler sampler = variant == 0 ? baseline.sampler() : optimized.sampler();
                            long start = System.nanoTime(), hash = 0;
                            for (int y = -8; y < 8; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                var climate = sampler.sample(10000 + round * 31 + x,y,-10000 - round * 41 + z);
                                hash = hash * 31 + climate.hashCode();
                            }
                            times[variant] = System.nanoTime() - start; hashes[variant] = hash; sink = hash;
                        }
                        assertEquals(hashes[0], hashes[1]);
                        if (round >= 0) {
                            var row = new LinkedHashMap<String,Object>(); row.put("round",round);
                            row.put("baselineMs",times[0]/1e6); row.put("optimizedMs",times[1]/1e6); row.put("hash",hashes[0]); rows.add(row);
                        }
                    }
                    var result = new LinkedHashMap<String,Object>(); result.put("rows",rows);
                    result.put("queriesPerRound",4096); result.put("scope","Climate input kernel; Java only, no rendering or terrain/decoration");
                    result.put("diagnostics",optimized.horizontalDiagnostics());
                    Path output = Path.of(System.getProperty("vss.climateBenchOutput")); Files.createDirectories(output.getParent());
                    Files.writeString(output,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                }
            }
        } finally {
            if (previous == null) System.clearProperty("vss.horizontalClimate"); else System.setProperty("vss.horizontalClimate",previous);
        }
    }
}
