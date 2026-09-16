package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import java.util.List;
class PredictionDimensionProfilesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static WorldgenProfileS2CPayload profile(String name, long seed, long revision) {
        return new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION,seed,revision,0,1,new byte[]{1},
                List.of(new DimensionProfile(ResourceLocation.parse(name),seed,-64,384,"noise","minecraft:overworld",revision)));
    }
    @Test void returnWithoutAnotherPacketRestoresOriginalSnapshotEvenDuringOtherDimensionDecode() {
        var cache = new PredictionDimensionProfiles();
        var a=profile("minecraft:overworld",42,1); var b=profile("northstar:earth_orbit",42,1);
        cache.remember(a);
        assertNull(cache.restore(Level.OVERWORLD,a));
        cache.remember(b);
        assertSame(a,cache.restore(Level.OVERWORLD,b),"return while B initializes must restore A without a packet");
        assertFalse(PredictionDimensionProfiles.contains(b,Level.OVERWORLD),"missing dimension cannot be marked initialized");
        cache.remember(a);
        assertSame(b,cache.restore(b.dimensions().get(0).levelKey(),a));
        assertNull(cache.restore(Level.NETHER,a));
    }
    @Test void revisionSeedAndConnectionChangesNeverRestoreStaleWorldgen() {
        var cache=new PredictionDimensionProfiles();
        var a=profile("minecraft:overworld",42,1);cache.remember(a);
        var reload=profile("northstar:earth_orbit",42,2);cache.remember(reload);
        assertNull(cache.restore(Level.OVERWORLD,reload));
        cache.remember(profile("minecraft:overworld",99,2));
        assertNull(cache.restore(reload.dimensions().get(0).levelKey(),null));
        cache.clear();assertEquals(0,cache.size());assertNull(cache.restore(Level.OVERWORLD,null));
    }
    @Test void retentionIsBoundedAndRecentlyVisitedDimensionSurvives() {
        var cache=new PredictionDimensionProfiles();
        var a=profile("minecraft:overworld",42,1);cache.remember(a);
        for(int i=0;i<3;i++) cache.remember(profile("test:d"+i,42,1));
        assertSame(a,cache.restore(Level.OVERWORLD,null));
        cache.remember(profile("test:new",42,1));
        assertEquals(4,cache.size());assertSame(a,cache.restore(Level.OVERWORLD,null));
        assertNull(cache.restore(profile("test:d0",42,1).dimensions().get(0).levelKey(),null));
    }
}
