package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.*;
import net.minecraft.world.level.levelgen.*;

/** Full released UTF router, compared with the generator's original column iterator. */
final class UltraTerraForgedExteriorReleasedTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void verify(ClassLoader loader, Object context, Object mixin, RegistryAccess access) throws Exception {
        String prefix = "com.pandaismyname1.ultraterraforged.";
        var vanilla = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var densities = new MappedRegistry<DensityFunction>(Registries.DENSITY_FUNCTION, Lifecycle.stable());
        var registration = densities.createRegistrationLookup();
        Map<ResourceKey<DensityFunction>,DensityFunction> values = new LinkedHashMap<>();
        vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements().forEach(h -> values.put(h.key(),h.value()));
        values.putIfAbsent(ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.parse("minecraft:zero")), DensityFunctions.zero());
        var bootstrap = new BootstrapContext<DensityFunction>() {
            @Override public Holder.Reference<DensityFunction> register(ResourceKey<DensityFunction> key, DensityFunction value, Lifecycle lifecycle) {
                values.put(key,value); return registration.getOrThrow(key);
            }
            @Override public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                return key.equals(Registries.DENSITY_FUNCTION) ? (HolderGetter<S>) registration : vanilla.lookupOrThrow(key);
            }
        };
        Object preset = context.getClass().getField("preset").get(context);
        var routerData = loader.loadClass(prefix + "data.preset.PresetNoiseRouterData");
        routerData.getMethod("bootstrap", preset.getClass(), BootstrapContext.class).invoke(null,preset,bootstrap);
        var identities = Collections.newSetFromMap(new IdentityHashMap<DensityFunction,Boolean>());
        values.forEach((key,value) -> {
            if (!identities.add(value) && value.minValue() == value.maxValue()) value = DensityFunctions.constant(value.minValue());
            densities.register(key,value,RegistrationInfo.BUILT_IN);
        }); densities.freeze();
        var overworld = routerData.getDeclaredMethod("overworld",preset.getClass(),HolderGetter.class,HolderGetter.class,HolderGetter.class);
        overworld.setAccessible(true);
        var noiseKey = ResourceKey.createRegistryKey(ResourceLocation.parse("ultraterraforged:worldgen/noise"));
        var router = (NoiseRouter) overworld.invoke(null,preset,densities.asLookup(),vanilla.lookupOrThrow(Registries.NOISE),access.registryOrThrow(noiseKey).asLookup());
        var base = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var settings = new NoiseGeneratorSettings(base.noiseSettings(),base.defaultBlock(),base.defaultFluid(),router,base.surfaceRule(),
                base.spawnTarget(),base.seaLevel(),base.disableMobGeneration(),false,false,base.useLegacyRandomSource());
        var initial = PredictionJavaExteriorTest.context(settings);
        var c = new PredictionJavaExteriorTest.Context(initial.generator(),RandomState.create(settings,vanilla.lookupOrThrow(Registries.NOISE),918273L),initial.heights());
        var redirect = Arrays.stream(mixin.getClass().getDeclaredMethods()).filter(m -> m.getName().equals("RandomState")).findFirst().orElseThrow();
        redirect.setAccessible(true);
        var mapped = (NoiseRouter) redirect.invoke(mixin,c.random().router(),(DensityFunction.Visitor) f -> f,settings,null,918273L);
        mapped = FreeTerraForgedDensity.map(context,mapped);
        var routerField = Arrays.stream(RandomState.class.getDeclaredFields()).filter(f -> f.getType() == NoiseRouter.class).findFirst().orElseThrow();
        routerField.setAccessible(true); routerField.set(c.random(),mapped);
        assertTrue(FreeTerraForgedExteriorOrder.canBatch(mapped), FreeTerraForgedExteriorOrder.reason(mapped));
        int comparisons = 0;
        try (var fast = new FreeTerraForgedExterior(c.generator(),c.random(),c.heights()); var request = fast.request()) {
            assertTrue(fast.batchesCells());
            for (int[] p : List.of(new int[]{-1,-5},new int[]{3,7},new int[]{15,-1},new int[]{19,7},new int[]{1607,-1453})) {
                var column = c.generator().getBaseColumn(p[0],p[1],c.heights(),c.random());
                int top = c.heights().getMaxBuildHeight();
                while (top > c.heights().getMinBuildHeight() && column.getBlock(top-1).isAir()) top--;
                if (top == c.heights().getMinBuildHeight()) continue;
                for (int step : new int[]{1,2,4}) for (int depth : new int[]{24,123,384}) {
                    int low = Math.max(c.heights().getMinBuildHeight(),top-depth);
                    assertArrayEquals(c.originalRange(p[0],p[1],step,low,top), fast.sample(new int[]{p[0]},new int[]{p[1]},step,
                            new int[]{low},new int[]{top},1,() -> true)[0],Arrays.toString(p)+" step="+step+" depth="+depth);
                    comparisons++;
                }
            }
            assertTrue(comparisons >= 9);
            System.out.println("Released UTF exterior: " + comparisons + " exact comparisons; " + fast.diagnostics());
        }
    }
}
