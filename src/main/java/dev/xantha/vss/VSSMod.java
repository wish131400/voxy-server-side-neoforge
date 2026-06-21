package dev.xantha.vss;

import dev.xantha.vss.client.VSSClientConfigScreens;
import dev.xantha.vss.client.VSSEmbeddiumOptionsEventBridge;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.server.FarPlayerBroadcaster;
import dev.xantha.vss.networking.server.VSSServerCommands;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(VSSConstants.MOD_ID)
public final class VSSMod {
    public VSSMod() {
        VSSNetworking.register();
        MinecraftForge.EVENT_BUS.register(VSSServerNetworking.class);
        MinecraftForge.EVENT_BUS.register(FarPlayerBroadcaster.class);
        MinecraftForge.EVENT_BUS.register(VSSServerCommands.class);
        MinecraftForge.EVENT_BUS.register(FTBChunksForceLoadCompat.class);
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> ClientInit::init);
    }

    private static final class ClientInit {
        private static void init() {
            VSSClientConfigScreens.register();
            VSSEmbeddiumOptionsEventBridge.register();
            ModCompat.init();
            MinecraftForge.EVENT_BUS.register(VSSClientNetworking.class);
            MinecraftForge.EVENT_BUS.register(FarPlayerClientRenderer.class);
        }
    }
}
