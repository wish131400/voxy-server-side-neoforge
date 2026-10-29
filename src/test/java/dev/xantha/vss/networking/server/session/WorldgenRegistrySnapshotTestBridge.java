package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonObject;
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

    public static JsonObject encode(RegistryAccess server) throws Exception {
        var encoded = WorldgenCodecSnapshot.encodeRegistries(server, null, new DensityFunctionReferences(),
                new WorldgenRegistryDependencies(server, List.of()));
        return JsonParser.parseString(new String(LodByteCompression.decompress(encoded.bytes(), encoded.compression(),
                encoded.rawSize(), 64 * 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
