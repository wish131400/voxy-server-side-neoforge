package dev.xantha.vss.compat;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class VoxyNotificationReplayTest {
 static Object make(Class<?> type,Object... args)throws Exception {
  for(var c:type.getDeclaredConstructors())if(c.getParameterCount()==args.length) {c.setAccessible(true);return c.newInstance(args);}
  throw new NoSuchMethodException(type.getName());
 }
 static void set(VoxyPredictionNotificationTest fixture,String name,Object value)throws Exception {
  var m=fixture.getClass().getDeclaredMethod("set",String.class,Object.class);m.setAccessible(true);m.invoke(fixture,name,value);
 }
 @Test void actualPredictionSourceUsesEarlyEvidenceAcrossDuplicatePublications()throws Exception {replay(false);}
 @Test void unrelatedNativeIngestKeepsActualPredictionReuse()throws Exception {replay(true);}
 private void replay(boolean nativeIngest)throws Exception {
  VoxyPredictionNotificationTest.bootstrap();var f=new VoxyPredictionNotificationTest();f.setup();
  String pkg="dev.xantha.vss.client.prediction.";
  var sourceClass=Class.forName(pkg+"PredictionVoxySource");Object source=make(sourceClass);
  try {
   set(f,"source",source);set(f,"skyLight",15);
   set(f,"blocks",new java.util.concurrent.ConcurrentHashMap<>(Map.of(Blocks.STONE.defaultBlockState(),1)));
   set(f,"biomes",new java.util.concurrent.ConcurrentHashMap<>(Map.of("minecraft:plains",1)));
   var key=make(Class.forName(pkg+"PredictionTileManager$PredictionTileKey"),Level.OVERWORLD,0,0,0);
   var columnClass=Class.forName(pkg+"PredictionVoxySource$Column");
   var run=make(Class.forName(pkg+"PredictionVoxySource$Run"),-64,64,Blocks.STONE.defaultBlockState());
   var column=make(columnClass,List.of(run),"minecraft:plains");
   var columns=Array.newInstance(columnClass,4096);for(int i=0;i<4096;i++)Array.set(columns,i,column);
   var tileClass=Class.forName(pkg+"PredictionVoxySource$Tile");
   var tile=make(tileClass,key,0,0,128,64,2,columns,List.of(),4096L,true,0);
   var put=sourceClass.getDeclaredMethod("put",tileClass);put.setAccessible(true);put.invoke(source,tile);
   var versions=f.getClass().getDeclaredMethod("versions");versions.setAccessible(true);
   @SuppressWarnings("unchecked")var v=(Map<Long,AtomicLong>)versions.invoke(f);
   v.put(VoxyPredictionBridge.position(2,0,0,0),new AtomicLong(1));
   var publish=f.getClass().getDeclaredMethod("publishNotification");publish.setAccessible(true);
   var engineField=f.getClass().getDeclaredField("engine");engineField.setAccessible(true);
   var engine=(VoxyPredictionNotificationTest.Engine)engineField.get(f);
   var before=VoxyPredictionBridge.diagnostics();long start=System.nanoTime();
   for(int i=0;i<25;i++) {
    if(nativeIngest)VoxyPredictionBridge.forwardEvent(engine,new VoxyPredictionNotificationTest.Router(0),
            new VoxyPredictionNotificationTest.Section(VoxyPredictionBridge.position(2,100+i,0,100)),1);
    publish.invoke(f);
   }
   var after=VoxyPredictionBridge.diagnostics();
   assertEquals(1,engine.events.get());
   long composed=((Number)after.get("sectionsComposed")).longValue()-((Number)before.get("sectionsComposed")).longValue();
   long early=((Number)after.getOrDefault("earlyUnchangedNotifications",0)).longValue()-((Number)before.getOrDefault("earlyUnchangedNotifications",0)).longValue();
   var diag=sourceClass.getDeclaredMethod("diagnostics");diag.setAccessible(true);
   @SuppressWarnings("unchecked")var prepared=(Map<String,Object>)diag.invoke(source);
   boolean baseline=Boolean.getBoolean("vss.replayBaseline");
   if(!baseline)assertEquals(1L,prepared.get("directSectionWrites"),"Cold notification writes directly into the native target");
   System.out.println((nativeIngest?"NOTIFICATION_LOCALITY_REPLAY":"NOTIFICATION_REPLAY")+" publications=25 events="+engine.events.get()+" composed="+composed+" early="+early+" direct="+prepared.get("directSectionWrites")+" wallMs="+(System.nanoTime()-start)/1e6+" source="+diag.invoke(source));
   assertEquals(baseline&&nativeIngest?25:1,composed);
   assertEquals(baseline&&nativeIngest?0:24,early);
   // Losing optional caches must cause one ordinary composition, never a
   // preliminary rasterization followed by a second copy/composition pass.
   var contextField=f.getClass().getDeclaredField("context");contextField.setAccessible(true);
   var mapper=(VoxyPredictionBridge.Mapper)contextField.get(f);
   var release=sourceClass.getDeclaredMethod("releaseMapper",VoxyPredictionBridge.Mapper.class);release.setAccessible(true);release.invoke(source,mapper);
   publish.invoke(f);
   @SuppressWarnings("unchecked")var rebuilt=(Map<String,Object>)diag.invoke(source);
   if(!baseline)assertEquals(2L,rebuilt.get("directSectionWrites"));
   assertEquals(2L,rebuilt.get("sectionBuilds"));
   assertEquals(1,engine.events.get(),"Cache loss must not change visible output");
  } finally {((AutoCloseable)source).close();f.cleanup();}
 }
}
