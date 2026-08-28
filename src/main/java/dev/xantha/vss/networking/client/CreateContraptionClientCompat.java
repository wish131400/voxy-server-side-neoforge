package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.entity.Entity;

final class CreateContraptionClientCompat {
    private static final String ABSTRACT_CONTRAPTION_ENTITY =
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final AtomicBoolean INVOCATION_FAILURE_LOGGED = new AtomicBoolean();
    private static volatile Reflection reflection;
    private static volatile Class<?> detectedContraptionEntityClass;
    private static volatile boolean createAbsent;
    private static volatile boolean resolutionFailed;

    private CreateContraptionClientCompat() {
    }

    static boolean isSafeToUse(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        Reflection resolved = reflection();
        Class<?> contraptionEntityClass = resolved != null
                ? resolved.contraptionEntityClass()
                : detectedContraptionEntityClass;
        if (contraptionEntityClass == null || !contraptionEntityClass.isInstance(entity)) {
            return true;
        }
        if (resolved == null) {
            return false;
        }
        try {
            return resolved.getContraption().invoke(entity) != null
                    && Boolean.TRUE.equals(resolved.isReadyForRender().invoke(entity));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (INVOCATION_FAILURE_LOGGED.compareAndSet(false, true)) {
                VSSLogger.warn("Failed to validate Create contraption entity " + entity.getType(), e);
            }
            return false;
        }
    }

    private static Reflection reflection() {
        if (createAbsent || resolutionFailed) {
            return null;
        }
        Reflection cached = reflection;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> contraptionEntityClass = Class.forName(ABSTRACT_CONTRAPTION_ENTITY);
            detectedContraptionEntityClass = contraptionEntityClass;
            Method getContraption = contraptionEntityClass.getMethod("getContraption");
            Method isReadyForRender = contraptionEntityClass.getMethod("isReadyForRender");
            Reflection resolved = new Reflection(contraptionEntityClass, getContraption, isReadyForRender);
            reflection = resolved;
            return resolved;
        } catch (ClassNotFoundException e) {
            createAbsent = true;
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            resolutionFailed = true;
            VSSLogger.warn("Create contraption readiness checks are unavailable", e);
            return null;
        }
    }

    private record Reflection(Class<?> contraptionEntityClass, Method getContraption, Method isReadyForRender) {
    }
}
