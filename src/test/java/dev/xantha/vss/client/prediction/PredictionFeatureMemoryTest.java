package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionFeatureMemoryTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    @Test void compactPagesAreLosslessForSignedExtremeCoordinatesAndAllBlockStates() {
        var original=new ArrayList<PredictionVoxySource.Box>();
        var states=net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream()
                .flatMap(b->b.getStateDefinition().getPossibleStates().stream()).limit(600).toList();
        for(int i=0;i<1100;i++) {
            int x=i%2==0?Integer.MIN_VALUE+i:Integer.MAX_VALUE-i;
            original.add(new PredictionVoxySource.Box(x,-100000+i,-300+i,x+1,100000+i,301+i,states.get(i%states.size())));
        }
        var packed=PredictionVoxyBoxes.of(original);
        assertEquals(original,packed);assertEquals(packed,original);assertEquals(original.hashCode(),packed.hashCode());
        assertThrows(UnsupportedOperationException.class,()->packed.add(original.get(0)));
        original.clear();assertEquals(1100,packed.size());
        assertEquals(Integer.MIN_VALUE,packed.get(0).minX());assertEquals(Integer.MAX_VALUE-1,packed.get(1).minX());
    }
    @Test void repeatedLodPagesShareWeaklyInternedStorageAndRetainIndependentOrigins() {
        var a=new PredictionVoxyBoxes.Builder();var b=new PredictionVoxyBoxes.Builder();
        var state=Blocks.OAK_LEAVES.defaultBlockState();
        for(int i=0;i<8192;i++) {
            int x=i%64,y=64+i%7,z=i/64;
            a.add(x,y,z,x+1,y+1,z+1,state);b.add(x-512,y,z+1024,x-511,y+1,z+1025,state);
        }
        var first=a.build();var second=b.build();
        for(int i=0;i<first.size();i+=256)assertSame(first.pageStorage(i),second.pageStorage(i));
        assertEquals(first.get(103).minX()-512,second.get(103).minX());
        assertTrue(first.bytes()<8192L*16,"Complete unit blocks use less than 16 bytes each including page overhead");
        assertSame(first,PredictionVoxyBoxes.of(first));
        System.out.printf(Locale.ROOT,"FEATURE_STORAGE blocks=%d previousEstimate=%d packedEstimate=%d indexEstimate=%d%n",
                first.size(),first.size()*64L,first.bytes(),new PredictionVoxyFeatures(first).bytes());
    }
    @Test void canonicalPublicationSharesTerrainDecorationAndRejectsStaleCommits() {
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var data=PredictionVoxelFixtures.flat(key,64,1,64);var base=data.voxyTile;
        var columns=base.columns().clone();
        var leaves=List.of(new PredictionVoxySource.Box(1,64,2,2,70,3,Blocks.OAK_LEAVES.defaultBlockState()));
        try(var source=new PredictionVoxySource()) {
            assertSame(base,source.put(base));
            var duplicate=new PredictionVoxySource.Tile(key,0,0,64,64,1,columns,List.of(),base.bytes(),false,0);
            assertSame(base,source.put(duplicate));
            var decorated=new PredictionVoxySource.Tile(key,0,0,64,64,1,columns,leaves,base.bytes()+64,true,0);
            var published=source.put(decorated);
            assertSame(base.columns(),published.columns());assertSame(decorated.features(),published.features());
            assertNull(source.put(base,()->false));assertSame(published,source.put(decorated));
        }
    }
}
