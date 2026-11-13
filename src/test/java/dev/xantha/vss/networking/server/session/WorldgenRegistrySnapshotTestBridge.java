package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.RegistryAccess;

/** Test-only access to the real server snapshot encoder across package boundaries. */
public final class WorldgenRegistrySnapshotTestBridge {
    private WorldgenRegistrySnapshotTestBridge() { }

    public static JsonElement biomeSource(net.minecraft.world.level.biome.BiomeSource source, RegistryAccess server) {
        return WorldgenCodecSnapshot.encodeBiomeSource(source, server, server.createSerializationContext(JsonOps.INSTANCE));
    }

    public static void resolveBiomePresets(JsonElement encoded, RegistryAccess server) {
        WorldgenCodecSnapshot.resolveMultiNoisePresets(encoded, server, server.createSerializationContext(JsonOps.INSTANCE));
    }

    public static JsonObject encode(RegistryAccess server) throws Exception {
        return encode(server, new WorldgenRegistryDependencies(server, List.of()));
    }

    public static JsonObject encode(RegistryAccess server, WorldgenRegistryDependencies dependencies) throws Exception {
        return JsonParser.parseString(new String(encodeBytes(server, dependencies), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** The uncompressed registry section bytes exactly as a profile build sends them. */
    public static byte[] encodeBytes(RegistryAccess server, WorldgenRegistryDependencies dependencies) throws Exception {
        var encoded = WorldgenCodecSnapshot.encodeRegistries(server, null, new DensityFunctionReferences(), dependencies);
        return LodByteCompression.decompress(encoded.bytes(), encoded.compression(), encoded.rawSize(), 64 * 1024 * 1024);
    }
}
