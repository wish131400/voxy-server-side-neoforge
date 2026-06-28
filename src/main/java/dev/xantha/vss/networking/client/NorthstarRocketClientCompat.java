package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

final class NorthstarRocketClientCompat {
    private static final String ROCKET_ENTITY_CLASS = "com.lightning.northstar.contraption.rocket.RocketContraptionEntity";
    private static final String CONTRAPTION_CLASS = "com.simibubi.create.content.contraptions.Contraption";
    private static final ResourceLocation TITANIUM_SPACE_DOOR = ResourceLocation.fromNamespaceAndPath("northstar", "titanium_space_door");
    private static final ResourceLocation ROCKET_FLAME = ResourceLocation.fromNamespaceAndPath("northstar", "rocket_flame");
    private static final ResourceLocation ROCKET_FLAME_LANDING = ResourceLocation.fromNamespaceAndPath("northstar", "rocket_flame_landing");
    private static final ResourceLocation ROCKET_SMOKE = ResourceLocation.fromNamespaceAndPath("northstar", "rocket_smoke");
    private static final ResourceLocation ROCKET_SMOKE_LANDING = ResourceLocation.fromNamespaceAndPath("northstar", "rocket_smoke_landing");
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    private static volatile Reflection reflection;
    private static volatile boolean unavailable;

    private NorthstarRocketClientCompat() {
    }

    static void apply(Entity entity, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
        if (entity == null || entity.isRemoved() || snapshot == null || !isRocketEntity(entity)) {
            return;
        }
        Reflection resolved = reflection();
        if (resolved == null) {
            return;
        }
        try {
            resolved.finalLiftVelocityField.setFloat(entity, snapshot.rocketFinalLiftVelocity());
            fixTitaniumSpaceDoors(entity, resolved);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to apply Northstar rocket client compat", e);
        }
    }

    static boolean isRocketEntity(Entity entity) {
        return entity != null && ROCKET_ENTITY_CLASS.equals(entity.getClass().getName());
    }

    static void tickParticles(Entity entity, float finalLiftVelocity, int tickCount) {
        if (entity == null || entity.isRemoved() || !isRocketEntity(entity)) {
            return;
        }
        spawnEngineParticles(entity, effectiveFinalLiftVelocity(entity, finalLiftVelocity), tickCount);
    }

    private static void fixTitaniumSpaceDoors(Entity entity, Reflection resolved)
            throws ReflectiveOperationException {
        Object contraption = resolved.getContraptionMethod.invoke(entity);
        if (contraption == null) {
            return;
        }
        Object rawBlocks = resolved.getBlocksMethod.invoke(contraption);
        if (!(rawBlocks instanceof Map<?, ?> blocks) || blocks.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<?, ?> entry : blocks.entrySet()) {
            if (!(entry.getKey() instanceof BlockPos pos)
                    || !(entry.getValue() instanceof StructureTemplate.StructureBlockInfo info)) {
                continue;
            }
            BlockState state = info.state();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            BooleanProperty visibleProperty = visibleProperty(state);
            if (!TITANIUM_SPACE_DOOR.equals(blockId) || visibleProperty == null) {
                continue;
            }
            Boolean visible = state.getValue(visibleProperty);
            if (Boolean.TRUE.equals(visible)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<BlockPos, StructureTemplate.StructureBlockInfo> typedBlocks =
                    (Map<BlockPos, StructureTemplate.StructureBlockInfo>) blocks;
            typedBlocks.put(pos, new StructureTemplate.StructureBlockInfo(
                    info.pos(),
                    state.setValue(visibleProperty, Boolean.TRUE),
                    info.nbt()));
            changed = true;
        }

        if (changed) {
            try {
                resolved.invalidateClientContraptionStructureMethod.invoke(contraption);
            } catch (ReflectiveOperationException ignored) {
                resolved.resetClientContraptionMethod.invoke(contraption);
            }
        }
    }

    private static BooleanProperty visibleProperty(BlockState state) {
        for (net.minecraft.world.level.block.state.properties.Property<?> property : state.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty && "visible".equals(booleanProperty.getName())) {
                return booleanProperty;
            }
        }
        return null;
    }

    private static void spawnEngineParticles(Entity entity, float finalLiftVelocity, int tickCount) {
        if (tickCount % PARTICLE_INTERVAL_TICKS != 0) {
            return;
        }
        float velocity = finalLiftVelocity;
        if (Math.abs(velocity) <= 0.5F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.particles().get().getId() == 2) {
            return;
        }

        boolean landing = velocity < -0.5F;
        ParticleOptions flame = particle(landing ? ROCKET_FLAME_LANDING : ROCKET_FLAME);
        ParticleOptions smoke = particle(landing ? ROCKET_SMOKE_LANDING : ROCKET_SMOKE);
        if (flame == null && smoke == null) {
            return;
        }

        double x = entity.getX();
        double y = entity.getY() - (landing ? 4.0D : 8.0D);
        double z = entity.getZ();
        double motionY = landing ? 0.08D : -0.08D;
        if (flame != null) {
            minecraft.level.addAlwaysVisibleParticle(flame, true, x, y, z, 0.0D, motionY, 0.0D);
        }
        if (smoke != null && tickCount % (PARTICLE_INTERVAL_TICKS * 2) == 0) {
            minecraft.level.addAlwaysVisibleParticle(smoke, true, x, y - 0.4D, z, 0.0D, motionY * 0.5D, 0.0D);
        }
    }

    private static float effectiveFinalLiftVelocity(Entity entity, float snapshotVelocity) {
        if (Math.abs(snapshotVelocity) > 0.5F) {
            return snapshotVelocity;
        }
        Reflection resolved = reflection();
        if (resolved == null) {
            return snapshotVelocity;
        }
        try {
            float entityVelocity = resolved.finalLiftVelocityField.getFloat(entity);
            if (Math.abs(entityVelocity) > 0.5F) {
                return entityVelocity;
            }
            return resolved.liftVelocityField.getFloat(entity);
        } catch (IllegalAccessException | RuntimeException e) {
            return snapshotVelocity;
        }
    }

    @SuppressWarnings("deprecation")
    private static ParticleOptions particle(ResourceLocation id) {
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(id);
        return type instanceof ParticleOptions options ? options : null;
    }

    private static Reflection reflection() {
        if (unavailable) {
            return null;
        }
        Reflection cached = reflection;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> rocketClass = Class.forName(ROCKET_ENTITY_CLASS);
            Field finalLiftVelocityField = rocketClass.getField("final_lift_vel");
            Field liftVelocityField = rocketClass.getField("lift_vel");
            Method getContraptionMethod = rocketClass.getMethod("getContraption");
            Class<?> contraptionClass = Class.forName(CONTRAPTION_CLASS);
            Method getBlocksMethod = contraptionClass.getMethod("getBlocks");
            Method resetClientContraptionMethod = contraptionClass.getMethod("resetClientContraption");
            Method invalidateClientContraptionStructureMethod =
                    contraptionClass.getMethod("invalidateClientContraptionStructure");
            Reflection resolved = new Reflection(
                    finalLiftVelocityField,
                    liftVelocityField,
                    getContraptionMethod,
                    getBlocksMethod,
                    resetClientContraptionMethod,
                    invalidateClientContraptionStructureMethod);
            reflection = resolved;
            return resolved;
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            VSSLogger.warn("Northstar rocket client compat is unavailable", e);
            return null;
        }
    }

    private record Reflection(
            Field finalLiftVelocityField,
            Field liftVelocityField,
            Method getContraptionMethod,
            Method getBlocksMethod,
            Method resetClientContraptionMethod,
            Method invalidateClientContraptionStructureMethod) {
    }
}
