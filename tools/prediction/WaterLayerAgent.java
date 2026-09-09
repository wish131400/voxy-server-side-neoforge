import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class WaterLayerAgent {
 public static void main(String[] a)throws Exception {var vm=com.sun.tools.attach.VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a[2]);}finally{vm.detach();}}
 public static void agentmain(String d,Instrumentation ins)throws Exception {
  Path p=Path.of(d);Files.createDirectories(p);
  Class<?> r=Arrays.stream(ins.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
  ClassLoader cl=r.getClassLoader();Class<?> mc=cl.loadClass("net.minecraft.client.Minecraft");Object client=mc.getMethod("getInstance").invoke(null);
  Object bus=cl.loadClass("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
  Class<?> event=cl.loadClass("net.neoforged.neoforge.client.event.RenderLevelStageEvent");
  Class<?> priority=cl.loadClass("net.neoforged.bus.api.EventPriority");
  Object lowest=priority.getField("LOWEST").get(null);
  java.util.concurrent.atomic.AtomicBoolean before=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.function.Consumer<Object>[] pre=new java.util.function.Consumer[1];
  pre[0]=value->{try{
   Object stage=value.getClass().getMethod("getStage").invoke(value);
   Object wanted=cl.loadClass("net.neoforged.neoforge.client.event.RenderLevelStageEvent$Stage").getField("AFTER_CUTOUT_BLOCKS").get(null);
   if(stage!=wanted || !before.compareAndSet(false,true))return;
   Object main=mc.getMethod("getMainRenderTarget").invoke(client);int w=main.getClass().getField("width").getInt(main),h=main.getClass().getField("height").getInt(main);
   var read=cl.loadClass("org.lwjgl.opengl.GL45C").getMethod("glGetTextureImage",int.class,int.class,int.class,int.class,ByteBuffer.class);
   tex(p,"before-depth",(int)call(main,"getDepthTextureId"),w,h,6402,5126,read);
   tex(p,"before-color",(int)call(main,"getColorTextureId"),w,h,6408,5121,read);
   cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("unregister",Object.class).invoke(bus,pre[0]);
  }catch(Throwable e){e.printStackTrace();}};
  cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("addListener",priority,boolean.class,Class.class,java.util.function.Consumer.class).invoke(bus,priority.getField("HIGHEST").get(null),false,event,pre[0]);
  java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.function.Consumer<Object>[] holder=new java.util.function.Consumer[1];
  holder[0]=value->{try{
    Object stage=value.getClass().getMethod("getStage").invoke(value);
    Object wanted=cl.loadClass("net.neoforged.neoforge.client.event.RenderLevelStageEvent$Stage").getField("AFTER_TRANSLUCENT_BLOCKS").get(null);
    if(stage!=wanted || !before.get() || !done.compareAndSet(false,true))return;
    try{capture(p,r,cl,mc,client);}finally{cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("unregister",Object.class).invoke(bus,holder[0]);}
   }catch(Throwable e){try{Files.writeString(p.resolve("error.txt"),e.toString()+"\n"+Arrays.toString(e.getStackTrace()));}catch(Exception ignored){}}};
  cl.loadClass("net.neoforged.bus.api.IEventBus").getMethod("addListener",priority,boolean.class,Class.class,java.util.function.Consumer.class).invoke(bus,lowest,false,event,holder[0]);
 }
 static Object field(Class<?> c,Object o,String n)throws Exception {var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static Object call(Object o,String n)throws Exception {var m=o.getClass().getMethod(n);m.setAccessible(true);return m.invoke(o);}
 static void capture(Path p,Class<?> r,ClassLoader cl,Class<?> mc,Object client)throws Exception{
  Object main=mc.getMethod("getMainRenderTarget").invoke(client);int w=main.getClass().getField("width").getInt(main),h=main.getClass().getField("height").getInt(main);
  var meta=new StringBuilder("width="+w+"\nheight="+h+"\n");meta.append("paused=").append(mc.getMethod("isPaused").invoke(client)).append('\n');
  Class<?> gl=cl.loadClass("org.lwjgl.opengl.GL45C");var read=gl.getMethod("glGetTextureImage",int.class,int.class,int.class,int.class,ByteBuffer.class);
  tex(p,"main-color",(int)call(main,"getColorTextureId"),w,h,6408,5121,read);
  tex(p,"main-depth",(int)call(main,"getDepthTextureId"),w,h,6402,5126,read);
  Object target=field(r,null,"predictionTarget");var depth=target.getClass().getDeclaredMethod("depthTextureId");depth.setAccessible(true);int dt=(int)depth.invoke(target);
  meta.append("predictionTexture=").append(dt).append('\n');if(dt>0)tex(p,"prediction-depth",dt,w,h,6402,5126,read);
  Class<?> vd=cl.loadClass("dev.xantha.vss.client.prediction.PredictionVoxyDepth");Object frame=field(vd,null,"frame");meta.append("voxyFrame=").append(frame).append('\n');
  if(frame!=null){int vw=(int)call(frame,"width"),vh=(int)call(frame,"height");meta.append("voxyWidth=").append(vw).append("\nvoxyHeight=").append(vh).append('\n');tex(p,"voxy-depth",(int)call(frame,"texture"),vw,vh,6402,5126,read);}
  meta.append(r.getMethod("diagnostics").invoke(null)).append('\n');
  Object terrain=field(r,null,"program");Object gp=field(terrain.getClass(),terrain,"program");
  for(Field f:gp.getClass().getDeclaredFields())if(f.getType()==int.class){f.setAccessible(true);meta.append("GlProgramField ").append(f.getName()).append('=').append(f.get(gp)).append('\n');}
  if(frame!=null){Object mat=call(frame,"inverseMvp");float[] values=new float[16];mat.getClass().getMethod("get",float[].class).invoke(mat,(Object)values);meta.append("inverseVoxy=").append(Arrays.toString(values)).append('\n');}

  int programId=(int)field(gp.getClass(),gp,"id");
  Class<?> gl20=cl.loadClass("org.lwjgl.opengl.GL20C");
  for(String name:new String[]{"ModelViewMat","ProjMat","MainDepthPlanes","VoxyDistanceNumerator","VoxyDistanceDenominator"}){
   int loc=(int)gl20.getMethod("glGetUniformLocation",int.class,CharSequence.class).invoke(null,programId,name);
   float[] vals=new float[16];gl20.getMethod("glGetUniformfv",int.class,int.class,float[].class).invoke(null,programId,loc,vals);meta.append(name).append('=').append(Arrays.toString(vals)).append('\n');
  }
  Files.writeString(p.resolve("meta.txt"),meta);
 }
 static void tex(Path p,String name,int id,int w,int h,int format,int type,Method read)throws Exception{
  ByteBuffer b=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());read.invoke(null,id,0,format,type,b);byte[] out=new byte[b.capacity()];b.get(out);Files.write(p.resolve(name+".bin"),out);
 }
}
