package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.network.FriendlyByteBuf;

/** One bounded, ordered transfer per connection, owned by the client thread. */
public final class WorldgenProfileAssembly {
    static final long IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(120);
    private final LongSupplier clock;
    private byte[] bytes;
    private int transfer;
    private int received;
    private long lastProgress;

    public WorldgenProfileAssembly() { this(System::nanoTime); }

    WorldgenProfileAssembly(LongSupplier clock) { this.clock = clock; }

    public WorldgenProfileS2CPayload accept(WorldgenProfileFragmentS2CPayload part) {
        expire();
        if (part.offset() == 0) {
            clear();
            bytes = new byte[part.total()];
            transfer = part.transfer();
        }
        if (bytes == null || transfer != part.transfer() || bytes.length != part.total()
                || received != part.offset()) {
            clear();
            throw new IllegalArgumentException("Out-of-order worldgen snapshot fragment");
        }
        System.arraycopy(part.data(), 0, bytes, received, part.data().length);
        received += part.data().length;
        lastProgress = clock.getAsLong();
        if (received != bytes.length) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        clear();
        try {
            WorldgenProfileS2CPayload result = WorldgenProfileS2CPayload.decode(buf);
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing worldgen snapshot data");
            return result;
        } finally {
            buf.release();
        }
    }

    /** Tick even without incoming packets so interrupted transfers do not retain memory. */
    public boolean expire() {
        if (bytes != null && clock.getAsLong() - lastProgress >= IDLE_TIMEOUT_NANOS) {
            clear();
            return true;
        }
        return false;
    }

    public void clear() { bytes = null; received = 0; }
}
