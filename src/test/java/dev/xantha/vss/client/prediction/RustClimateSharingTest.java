package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RustClimateSharingTest {
    @BeforeAll static void bootstrap() {
        DensityMemoBenchTest.bootstrap();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        assertTrue(RustTerrainSampler.available());
    }
    private static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    @Test void sharesOnlyMatchingInputsAndRetainsJavaDepthArithmetic() throws Exception {
        var document = LithostitchedNativeTest.document();
        long world = RustWorldgenBackend.create(42, 0, document.toString());
        try (var context = DensityMemoBenchTest.sampler(document, 42);
             var optimized = new PredictionClimateSampler(context.randomStateContext().sampler())) {
            var source = context.randomStateContext().sampler();
            optimized.bindHorizontal(world, context.randomStateContext(), document, 42);
            var input = direct(12); var output = direct(8);
            String[] names = {"temperature","vegetation","continents","erosion","depth","ridges"};
            for (int x = -32; x < 32; x += 4) for (int z = -16; z < 16; z += 4) {
                input.putInt(0,x).putInt(4,63).putInt(8,z);
                for (String name : names) assertEquals(1,RustWorldgenBackend.density(world,name,input,output,1));
                for (int y : new int[]{-64,-1,63,319}) assertEquals(source.sample(x/4,y/4,z/4),optimized.sampler().sample(x/4,y/4,z/4));
                var a = PredictionClimateSamplerTest.roots(source); var b = PredictionClimateSamplerTest.roots(optimized.sampler());
                for (int y : new int[]{-64,-1,63,319}) for (int lane=0;lane<6;lane++) {
                    var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(x,y,z);
                    assertEquals(Double.doubleToRawLongBits(a[lane].compute(point)),Double.doubleToRawLongBits(b[lane].compute(point)));
                }
            }
            assertFalse(optimized.horizontalDiagnostics().contains("horizontalCopied=0"), optimized.horizontalDiagnostics());
            String beforeRejectedBinding = optimized.horizontalDiagnostics().split("horizontalBatches=")[1].split(",")[0];
            optimized.bindHorizontal(world, context.randomStateContext(), document, 43);
            assertEquals(source.sample(1000,7,-1000),optimized.sampler().sample(1000,7,-1000));
            assertEquals(beforeRejectedBinding,optimized.horizontalDiagnostics().split("horizontalBatches=")[1].split(",")[0],"Rejected replacement must detach the previous native world");
            optimized.detachHorizontal();

            assertEquals(source.sample(-3,7,11),optimized.sampler().sample(-3,7,11));
            // Missing inputs do not trigger a native calculation, including negative coordinates.
            var xz = direct(8).putInt(0,100004).putInt(4,-100008); var rows = direct(56);
            assertEquals(1,RustWorldgenBackend.horizontalLookup(world,xz,rows,1)); assertEquals(0,rows.getLong(0));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.horizontalLookup(world,xz,rows,17));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.horizontalLookup(world,xz,ByteBuffer.allocate(56),1));
        } finally { RustWorldgenBackend.close(world); }
    }
}
