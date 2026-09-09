import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Compare the live server generator with prediction without generating/storing chunks. */
public class PredictionHeightOracleAgent {
    public static void main(String[] args) throws Exception {
        var vm=com.sun.tools.attach.VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1],args[2]);} finally {vm.detach();}
    }
    public static void agentmain(String directory,Instrumentation instrumentation)throws Exception {
        Path output=Path.of(directory);Files.createDirectories(output);
        try {
            Class<?> state=Arrays.stream(instrumentation.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
            ClassLoader loader=state.getClassLoader();
            Object mc=invoke(loader.loadClass("net.minecraft.client.Minecraft"),null,"getInstance");
            Object server=call(mc,"getSingleplayerServer");
            var work=new FutureTask<String>(()-> {
                Object level=call(server,"overworld"),source=call(level,"getChunkSource");
                Object generator=call(source,"getGenerator"),random=call(source,"randomState");
                Object manager=((Map<?,?>)field(state,null,"MANAGERS")).get(call(level,"dimension"));
                Object sampler=field(manager.getClass(),manager,"sampler");
                Object context=field(sampler.getClass(),sampler,"context");
                Object predictedRandom=invoke(context.getClass(),context,"randomStateContext");
                long handle=(long)field(sampler.getClass(),sampler,"world");
                var density=loader.loadClass("dev.xantha.vss.client.prediction.RustWorldgenBackend").getMethod("density",long.class,String.class,ByteBuffer.class,ByteBuffer.class,int.class);
                var input=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
                var result=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
                var point=loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction$SinglePointContext").getConstructor(int.class,int.class,int.class);
                StringBuilder text=new StringBuilder("seed="+call(level,"getSeed")+" generator="+generator.getClass().getName()+"\n");
                for(int[] p:new int[][]{{-186,-50},{-128,0},{0,0},{71,217},{256,256},{-512,-512},{512,-512},{-1024,0},{0,1024},{2048,2048},{-4096,1024},{8192,-8192},{-16384,16384}}) {
                    Object column=call(generator,"getBaseColumn",p[0],p[1],level,random);
                    int min=(int)call(level,"getMinBuildHeight"),max=(int)call(level,"getMaxBuildHeight"),floor=min;
                    for(int y=max-1;y>=min;y--) {
                        Object block=call(column,"getBlock",y);
                        if(!(boolean)call(block,"isAir") && (boolean)call(call(block,"getFluidState"),"isEmpty")) {floor=y+1;break;}
                    }
                    text.append("COLUMN ").append(Arrays.toString(p)).append(" serverBase=").append(floor)
                        .append(" rustSurface=").append(call(sampler,"surfaceY",p[0],p[1])).append('\n');
                    for(int y:new int[]{0,64,128,192,256}) {
                        Object pos=point.newInstance(p[0],y,p[1]);
                        for(String root:new String[]{"finalDensity","initialDensityWithoutJaggedness","continents","depth"}) {
                            Object actual=call(call(random,"router"),root),predicted=call(call(predictedRandom,"router"),root);
                            String nativeRoot=switch(root){case "finalDensity"->"final_density";case "initialDensityWithoutJaggedness"->"initial_density_without_jaggedness";default->root;};
                            input.putInt(0,p[0]).putInt(4,y).putInt(8,p[1]);density.invoke(null,handle,nativeRoot,input,result,1);
                            text.append("DENSITY ").append(Arrays.toString(p)).append(" y=").append(y).append(' ').append(root)
                                .append(" server=").append(call(actual,"compute",pos)).append(" clientJava=").append(call(predicted,"compute",pos))
                                .append(" rust=").append(result.getDouble(0)).append('\n');
                        }
                    }
                }
                return text.toString();
            });
            call(server,"execute",work);
            Files.writeString(output.resolve("height-oracle.txt"),work.get(30,TimeUnit.SECONDS));
        } catch(Throwable failure) {Files.writeString(output.resolve("height-oracle-error.txt"),stacktrace(failure));throw failure;}
    }
    static String stacktrace(Throwable failure) { var writer=new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(writer));return writer.toString(); }
    static Object field(Class<?> type,Object object,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Object call(Object target,String name,Object...args)throws Exception {return invoke(target.getClass(),target,name,args);}
    static Object invoke(Class<?> type,Object target,String name,Object...args)throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods()) {
            if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
                boolean fits=true;Class<?>[] params=m.getParameterTypes();
                for(int i=0;i<args.length;i++)if(args[i]!=null && !params[i].isPrimitive() && !params[i].isInstance(args[i]))fits=false;
                if(fits){m.setAccessible(true);return m.invoke(target,args);}
            }
        }
        for(Method m:type.getMethods()) {
            if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
                boolean fits=true;Class<?>[] params=m.getParameterTypes();
                for(int i=0;i<args.length;i++)if(args[i]!=null && !params[i].isPrimitive() && !params[i].isInstance(args[i]))fits=false;
                if(fits){m.setAccessible(true);return m.invoke(target,args);}
            }
        }
        throw new NoSuchMethodException(type+"."+name);
    }
}
