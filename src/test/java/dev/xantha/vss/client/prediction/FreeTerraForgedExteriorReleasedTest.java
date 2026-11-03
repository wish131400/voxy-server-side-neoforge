package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.*;

/** Invoked by the released-runtime fixture after its real filtered terrain context is initialized. */
final class FreeTerraForgedExteriorReleasedTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void verify(ClassLoader loader, Object context, Object mixin, RegistryAccess access) throws Exception {
        String prefix="etcodehome.freeterraforged.";
        var vanilla=net.minecraft.data.registries.VanillaRegistries.createLookup();
        var densities=new MappedRegistry<DensityFunction>(Registries.DENSITY_FUNCTION,Lifecycle.stable());
        var registration=densities.createRegistrationLookup();
        Map<ResourceKey<DensityFunction>,DensityFunction> values=new LinkedHashMap<>();
        vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements().forEach(holder->values.put(holder.key(),holder.value()));
        values.putIfAbsent(ResourceKey.create(Registries.DENSITY_FUNCTION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:zero")),DensityFunctions.zero());
        var bootstrap=new BootstrapContext<DensityFunction>() {
            @Override public Holder.Reference<DensityFunction> register(ResourceKey<DensityFunction> key,DensityFunction value,Lifecycle lifecycle) {
                values.put(key,value);return registration.getOrThrow(key);
            }
            @Override public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                if(key.equals(Registries.DENSITY_FUNCTION))return (HolderGetter<S>)registration;
                return vanilla.lookupOrThrow(key);
            }
        };
        Object preset=context.getClass().getField("preset").get(context);
        var routerData=loader.loadClass(prefix+"data.worldgen.preset.PresetNoiseRouterData");
        routerData.getMethod("bootstrap",preset.getClass(),BootstrapContext.class).invoke(null,preset,bootstrap);
        var identities=Collections.newSetFromMap(new IdentityHashMap<DensityFunction,Boolean>());
        values.forEach((key,value)-> {
            // Vanilla's test registry binds holders by value identity; aliases need their own constant object.
            if(!identities.add(value) && value.minValue()==value.maxValue())value=DensityFunctions.constant(value.minValue());
            densities.register(key,value,RegistrationInfo.BUILT_IN);
        });densities.freeze();
        var noiseKey=ResourceKey.createRegistryKey(net.minecraft.resources.ResourceLocation.parse("freeterraforged:worldgen/noise"));
        var overworld=routerData.getDeclaredMethod("overworld",preset.getClass(),HolderGetter.class,HolderGetter.class,HolderGetter.class);
        overworld.setAccessible(true);
        var router=(NoiseRouter)overworld.invoke(null,preset,densities.asLookup(),vanilla.lookupOrThrow(Registries.NOISE),access.registryOrThrow(noiseKey).asLookup());
        var base=vanilla.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var settings=new NoiseGeneratorSettings(base.noiseSettings(),base.defaultBlock(),base.defaultFluid(),router,base.surfaceRule(),
                base.spawnTarget(),base.seaLevel(),base.disableMobGeneration(),base.isAquifersEnabled(),base.oreVeinsEnabled(),base.useLegacyRandomSource());
        var initial=PredictionJavaExteriorTest.context(settings);
        var c=new PredictionJavaExteriorTest.Context(initial.generator(),RandomState.create(settings,
                vanilla.lookupOrThrow(Registries.NOISE),918273L),initial.heights());
        var redirect=Arrays.stream(mixin.getClass().getDeclaredMethods()).filter(m->m.getName().equals("RandomState")).findFirst().orElseThrow();
        redirect.setAccessible(true);
        // Seed vanilla noises first, then run the release's CellSampler/NoiseFunction mapping.
        var mapped=(NoiseRouter)redirect.invoke(mixin,c.random().router(),(DensityFunction.Visitor)f->f,settings,null,918273L);
        mapped=FreeTerraForgedDensity.map(context,mapped);
        var routerField=Arrays.stream(RandomState.class.getDeclaredFields()).filter(f->f.getType()==NoiseRouter.class).findFirst().orElseThrow();
        routerField.setAccessible(true);routerField.set(c.random(),mapped);
        assertTrue(FreeTerraForgedExteriorOrder.canBatch(mapped),"the real released FTF graph should qualify for cell batching: "
                +FreeTerraForgedExteriorOrder.reason(mapped));
        try(var fast=new FreeTerraForgedExterior(c.generator(),c.random(),c.heights())) {
            int comparisons=0;
            for(int[] p:List.of(new int[]{-1,-5},new int[]{3,7},new int[]{63,63},new int[]{64,64},new int[]{1607,-1453})) {
                var col=c.generator().getBaseColumn(p[0],p[1],c.heights(),c.random());
                int high=c.heights().getMaxBuildHeight();while(high>c.heights().getMinBuildHeight()&&col.getBlock(high-1).isAir())high--;
                if(high==c.heights().getMinBuildHeight())continue;
                for(int step:new int[]{1,2,4}) for(int depth:new int[]{123,c.heights().getHeight()}) {
                    int low=Math.max(c.heights().getMinBuildHeight(),high-depth);
                    assertArrayEquals(c.originalRange(p[0],p[1],step,low,high),fast.sample(new int[]{p[0]},new int[]{p[1]},step,
                            new int[]{low},new int[]{high},1,()->true)[0],Arrays.toString(p)+" step="+step);
                    comparisons++;
                }
            }
            assertTrue(comparisons>=6);
            System.out.println("Released FTF exterior: "+comparisons+" exact comparisons; "+fast.diagnostics());
        }
        verifyConditionalCache(loader);
        verifyCompiledWrapper(mapped);
        benchmark(c);
    }

    private static void verifyConditionalCache(ClassLoader loader) throws Exception {
        var constructor=loader.loadClass("etcodehome.freeterraforged.world.worldgen.densityfunction.ConditionalFlatCache")
                .getConstructor(DensityFunction.class);
        var horizontal=(DensityFunction)constructor.newInstance(DensityFunctions.constant(1));
        var vertical=(DensityFunction)constructor.newInstance(DensityFunctions.yClampedGradient(-64,320,1,-1));
        assertTrue(FreeTerraForgedExteriorOrder.canBatch(PredictionJavaExteriorTest.router(horizontal)));
        assertFalse(FreeTerraForgedExteriorOrder.canBatch(PredictionJavaExteriorTest.router(vertical)));
    }

    private static void verifyCompiledWrapper(NoiseRouter mapped) throws Exception {
        String jar=System.getProperty("vss.c2meDfcJar");if(jar==null)return;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(jar).toUri().toURL()},
                FreeTerraForgedExteriorReleasedTest.class.getClassLoader())) {
            var type=loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.SubCompiledDensityFunction");
            var constructor=Arrays.stream(type.getConstructors()).filter(c->c.getParameterCount()==3).findFirst().orElseThrow();
            // Test fallback ownership with the actual wrapper. The uncalled evaluator proxies deliberately fail.
            Object single=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{constructor.getParameterTypes()[0]},
                    (p,m,args)->{throw new AssertionError("This test inspects the compiled wrapper's fallback only");});
            Object multi=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{constructor.getParameterTypes()[1]},
                    (p,m,args)->{throw new AssertionError("This test inspects the compiled wrapper's fallback only");});
            DensityFunction wrapped=(DensityFunction)constructor.newInstance(single,multi,mapped.finalDensity());
            assertTrue(FreeTerraForgedExteriorOrder.canBatch(PredictionJavaExteriorTest.router(wrapped)));
            DensityFunction unsafe=(DensityFunction)constructor.newInstance(single,multi,PredictionJavaExteriorTest.orderedLayers());
            assertFalse(FreeTerraForgedExteriorOrder.canBatch(PredictionJavaExteriorTest.router(unsafe)));
        }
    }

    private static void benchmark(PredictionJavaExteriorTest.Context c) {
        int count=32;int[] xs=new int[count],zs=new int[count],lows=new int[count],tops=new int[count];
        for(int i=0;i<count;i++) {
            xs[i]=(i%8)*4;zs[i]=(i/8)*4;
            var col=c.generator().getBaseColumn(xs[i],zs[i],c.heights(),c.random());
            int top=c.heights().getMaxBuildHeight();while(top>c.heights().getMinBuildHeight()&&col.getBlock(top-1).isAir())top--;
            tops[i]=top;lows[i]=c.heights().getMinBuildHeight();
        }
        var old=new PredictionJavaExterior(c.generator(),c.random(),c.heights());
        var expected=new boolean[count][];
        // Prime JIT and upstream terrain cells; the measured occupancy caches start empty.
        for(int i=0;i<4;i++)old.sample(xs[i],zs[i],4,lows[i],tops[i],()->true);
        long start=System.nanoTime();
        for(int i=0;i<count;i++)expected[i]=old.sample(xs[i],zs[i],4,lows[i],tops[i],()->true);
        long baseline=System.nanoTime()-start;
        try(var fast=new FreeTerraForgedExterior(c.generator(),c.random(),c.heights())) {
            start=System.nanoTime();
            var cold=fast.sample(xs,zs,4,lows,tops,count,()->true);
            long coldNanos=System.nanoTime()-start;
            start=System.nanoTime();var warm=fast.sample(xs,zs,4,lows,tops,count,()->true);long warmNanos=System.nanoTime()-start;
            for(int i=0;i<count;i++){assertArrayEquals(expected[i],cold[i]);assertArrayEquals(expected[i],warm[i]);}
            System.out.printf(Locale.ROOT,"Released FTF 32 footprints, full-depth, upstream terrain warm: previous=%.3f ms; occupancy cold=%.3f ms; occupancy warm=%.3f ms; %s%n",
                    baseline/1e6,coldNanos/1e6,warmNanos/1e6,fast.diagnostics());
        }
    }
}
