package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** User-triggered export of the current world's reproducible inputs and native reference outputs. */
public final class RustReferenceExport {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private RustReferenceExport() { }

    public static CompletableFuture<Path> start() {
        Minecraft minecraft = Minecraft.getInstance();
        var payload = ClientPredictionState.referenceProfile();
        var storage = PredictionCacheStorage.current();
        if (payload == null || storage == null || minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null)
            return CompletableFuture.failedFuture(new IllegalStateException("World generation profile is not ready"));
        var dimension = minecraft.level.dimension().location();
        var selected = payload.dimensions().stream().filter(p -> p.dimension().equals(dimension)).findFirst();
        if (selected.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("No profile for current dimension"));
        if (!RUNNING.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("A reference capture is already running"));
        var access = minecraft.getConnection().registryAccess();
        int x = minecraft.player.getBlockX() - 2, z = minecraft.player.getBlockZ() - 2;
        var colors = new java.util.HashMap<String, byte[]>();
        try {
            for (String name : java.util.List.of("grass", "foliage")) {
                try (var in = minecraft.getResourceManager().open(ResourceLocation.withDefaultNamespace("textures/colormap/" + name + ".png"))) {
                    colors.put(name + "-colormap.png", in.readAllBytes());
                }
            }
        } catch (Exception failure) { RUNNING.set(false); return CompletableFuture.failedFuture(failure); }
        return CompletableFuture.supplyAsync(() -> {
            try {
                var profile = selected.get();
                Path root = storage.base().resolveSibling("rust-reference");
                Files.createDirectories(root);
                Path output = Files.createTempDirectory(root, "capture-");
                for (var entry : colors.entrySet()) Files.write(output.resolve(entry.getKey()), entry.getValue(), StandardOpenOption.CREATE_NEW);
                FriendlyByteBuf encoded = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    WorldgenProfileS2CPayload.encode(payload, encoded);
                    byte[] bytes = new byte[encoded.readableBytes()]; encoded.readBytes(bytes);
                    Files.write(output.resolve("worldgen-profile.bin"), bytes, StandardOpenOption.CREATE_NEW);
                } finally { encoded.release(); }
                byte[] registries = LodByteCompression.decompress(payload.registries(), payload.registriesCompression(),
                        payload.registriesRawSize(), WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES);
                byte[] generator = LodByteCompression.decompress(profile.generatorData(), profile.generatorCompression(),
                        profile.generatorRawSize(), WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES);
                var decoded = ClientWorldgenRegistries.decode(JsonParser.parseString(new String(registries, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(), access);
                var context = ClientWorldgenProfileDecoder.decodeJavaSampler(profile, generator, decoded, access);
                RustReferenceCapture.capture(output, profile, generator, registries, context, x, z);
                return output;
            } catch (Exception failure) { throw new CompletionException(failure); }
            finally { RUNNING.set(false); }
        }, task -> {
            Thread thread = new Thread(task, "vss-rust-reference");
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); thread.start();
        });
    }
}
