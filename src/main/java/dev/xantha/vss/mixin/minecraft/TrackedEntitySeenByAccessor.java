package dev.xantha.vss.mixin.minecraft;

import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntitySeenByAccessor {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> vss$getSeenBy();
}
