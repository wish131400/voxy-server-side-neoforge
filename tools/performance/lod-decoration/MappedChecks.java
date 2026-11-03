package dev.xantha.vss.client.prediction;

import java.util.*;
import java.security.MessageDigest;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import dev.xantha.vss.compat.VoxyPredictionBridge;

/** Exercise the actual source conversion at levels 0..4; geometry equality alone is insufficient. */
final class MappedChecks {
    static final Method BUILD;
    static {
        try{BUILD=PredictionVoxySource.class.getDeclaredMethod("buildSection",long.class,VoxyPredictionBridge.Mapper.class,List.class,long[].class,boolean.class);BUILD.setAccessible(true);}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String fingerprint(PredictionVoxySource.Tile tile)throws Exception{
        var points=new TreeSet<Long>();
        for(var b:tile.features()){
            for(int x=b.minX();x<b.maxX();x++)for(int z=b.minZ();z<b.maxZ();z++)for(int y=b.minY();y<b.maxY();y++)
                points.add(BlockPos.asLong(tile.baseX()+x,y,tile.baseZ()+z));
        }
        var chosen=new ArrayList<Long>();var sorted=new ArrayList<>(points);
        for(int i=0;i<8 && !sorted.isEmpty();i++)chosen.add(sorted.get(i*(sorted.size()-1)/7));
        chosen.add(BlockPos.asLong(tile.baseX()+tile.span()/2,64,tile.baseZ()+tile.span()/2));
        var sections=new TreeSet<Long>();
        for(int level=0;level<=4;level++)for(long p:chosen){int span=32<<level;sections.add(VoxyPredictionBridge.position(level,Math.floorDiv(BlockPos.getX(p),span),Math.floorDiv(BlockPos.getY(p),span),Math.floorDiv(BlockPos.getZ(p),span)));}
        VoxyPredictionBridge.Mapper mapper=(state,biome)->state.isAir()?15L<<56:(15L<<56)|((long)(Block.BLOCK_STATE_REGISTRY.getId(state)+1)<<27)|((long)(biome.hashCode()&511)<<47);
        var digest=MessageDigest.getInstance("SHA-256");
        var buffer=java.nio.ByteBuffer.allocate(32768*8);
        try(var source=new PredictionVoxySource()){
            for(long position:sections){
                long[] data=(long[])BUILD.invoke(source,position,mapper,List.of(tile),new long[32768],false);
                buffer.clear();for(long value:data)buffer.putLong(value);digest.update(buffer.array());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
