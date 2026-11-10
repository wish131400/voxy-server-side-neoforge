package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaSurfaceConditionsTest {
    public record Info(int value) { }
    public record Definition(int getRarityOffset) { }
    public static class Rarity {
        static Info cell; static String key; static long seed; static int qx,qz,x,z;
        static boolean fail;
        public static Info getRareBiomeInfoForQuad(long s,int a,int b) {
            if(fail)throw new IllegalArgumentException("mod error");
            seed=s;qx=a;qz=b;return cell;
        }
        public static int getRareBiomeOffsetId(Info info) {return info.value();}
        public static String getACBiomeForPosition(long s,int a,int b) {x=a;z=b;return key;}
    }
    public static class Config {
        static Map<String,Definition> entries;
        public static Map<String,Definition> getBiomesSnapshot() {return entries;}
    }
    private JavaSurfaceConditions.Adapter adapter;
    @BeforeEach void prepare() throws Exception {
        adapter=new JavaSurfaceConditions.Adapter(Rarity.class,Config.class,Definition.class);
        Rarity.cell=new Info(2);Rarity.key="cave";Rarity.fail=false;
        Config.entries=Map.of("cave",new Definition(2));
    }
    @Test void preservesSeedBlockCoordinatesAndSignedQuartShift() {
        assertTrue(adapter.matches(1234,-1,-5,2));assertEquals(1234,Rarity.seed);
        assertEquals(-1,Rarity.qx);assertEquals(-2,Rarity.qz);assertEquals(-1,Rarity.x);assertEquals(-5,Rarity.z);
    }
    @Test void missingOrMismatchedRarityAndConfigurationDoNotMatch() {
        assertFalse(adapter.matches(1,0,0,3));
        Rarity.cell=null;assertFalse(adapter.matches(1,0,0,2));
        Rarity.cell=new Info(2);Rarity.key=null;assertFalse(adapter.matches(1,0,0,2));
        Rarity.key="cave";Config.entries=Map.of();assertFalse(adapter.matches(1,0,0,2));
        Config.entries=Map.of("cave",new Definition(4));assertFalse(adapter.matches(1,0,0,2));
    }
    @Test void evaluationErrorsPropagateInsteadOfInventingFalseMaterials() {
        Rarity.fail=true;
        var error=assertThrows(IllegalStateException.class,()->adapter.matches(1,0,0,2));
        assertEquals("mod error",error.getCause().getMessage());
    }
    @Test void incompatibleApisRejectBeforeWorkAndHighYNeedsNoMod() {
        assertThrows(NoSuchMethodException.class,()->new JavaSurfaceConditions.Adapter(Object.class,Config.class,Definition.class));
        assertFalse(JavaSurfaceConditions.alexCavesBiome(1,0,51,0,2));
        assertFalse(JavaSurfaceConditions.alexCavesBiome(0,0,0,0,2));
    }
}
