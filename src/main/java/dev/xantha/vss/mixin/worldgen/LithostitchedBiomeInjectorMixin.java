package dev.xantha.vss.mixin.worldgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.xantha.vss.common.worldgen.LithostitchedBiomeSnapshot;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = LithostitchedBiomeSnapshot.MANAGER, remap = false)
abstract class LithostitchedBiomeInjectorMixin {
    private static final String LEGACY = "Ldev/worldgen/lithostitched/impl/worldgen/biomeinjector/internal/InjectorBiomeSource;applyInjectors(Ljava/util/Map;Ljava/util/Optional;Ljava/util/Map;Ldev/worldgen/lithostitched/api/worldgen/util/DensityFunctionWrapper;)V";
    private static final String MODERN = "Ldev/worldgen/lithostitched/impl/worldgen/biomeinjector/internal/InjectorBiomeSource;applyInjectors(Ljava/util/Map;Ljava/util/Optional;Ljava/util/Map;Lnet/minecraft/world/level/levelgen/RandomState;)V";

    @WrapOperation(method = "applyBiomeInjectors", at = @At(value = "INVOKE", target = LEGACY), require = 0, remap = false)
    private static void vss$captureLegacy(@Coerce Object source, Map<?, ?> injectors, Optional<?> function,
            Map<?, ?> regions, @Coerce Object binder, Operation<Void> next,
            @Local(argsOnly = true) RegistryAccess access) {
        LithostitchedBiomeSnapshot.capture((BiomeSource) source, access, injectors, function, regions);
        next.call(source, injectors, function, regions, binder);
    }

    @WrapOperation(method = "applyBiomeInjectors", at = @At(value = "INVOKE", target = MODERN), require = 0, remap = false)
    private static void vss$captureModern(@Coerce Object source, Map<?, ?> injectors, Optional<?> function,
            Map<?, ?> regions, @Coerce Object binder, Operation<Void> next,
            @Local(argsOnly = true) RegistryAccess access) {
        LithostitchedBiomeSnapshot.capture((BiomeSource) source, access, injectors, function, regions);
        next.call(source, injectors, function, regions, binder);
    }
}
