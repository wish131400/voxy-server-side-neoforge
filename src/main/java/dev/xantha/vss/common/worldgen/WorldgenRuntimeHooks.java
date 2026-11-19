package dev.xantha.vss.common.worldgen;

import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Runtime behavior can change on a vanilla class without introducing a subclass. */
public final class WorldgenRuntimeHooks {
    private WorldgenRuntimeHooks() { }
    private static final ClassValue<Boolean> CHANGED = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> current=type; current!=null && current!=Object.class; current=current.getSuperclass()) {
                for (Method method:current.getDeclaredMethods()) {
                    var merged=method.getDeclaredAnnotation(MixinMerged.class);
                    if (merged!=null && !merged.mixin().startsWith("dev.xantha.vss.")
                            && method.getDeclaredAnnotation(org.spongepowered.asm.mixin.gen.Accessor.class)==null
                            && method.getDeclaredAnnotation(org.spongepowered.asm.mixin.gen.Invoker.class)==null)
                        return true;
                }
            }
            return false;
        }
    };
    public static boolean changed(Class<?> type) { return CHANGED.get(type); }
}
