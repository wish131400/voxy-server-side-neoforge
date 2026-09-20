package dev.xantha.vss.networking;

import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;

/** Encode completely before sending, then emit packets below both loader and vanilla limits. */
public final class WorldgenProfileTransfer {
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    private WorldgenProfileTransfer() {}

    public static void send(WorldgenProfileS2CPayload profile, Consumer<WorldgenProfileFragmentS2CPayload> sender) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(
                WorldgenProfileFragmentS2CPayload.CHUNK_BYTES, WorldgenProfileFragmentS2CPayload.MAX_BYTES));
        try {
            WorldgenProfileS2CPayload.encode(profile, buf);
            int total = buf.readableBytes();
            int transfer = TRANSFERS.incrementAndGet();
            for (int offset = 0; offset < total;) {
                byte[] part = new byte[Math.min(WorldgenProfileFragmentS2CPayload.CHUNK_BYTES, total - offset)];
                buf.readBytes(part);
                sender.accept(new WorldgenProfileFragmentS2CPayload(transfer, total, offset, part));
                offset += part.length;
            }
        } finally {
            buf.release();
        }
    }
}
