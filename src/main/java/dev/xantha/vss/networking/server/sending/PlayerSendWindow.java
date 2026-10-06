package dev.xantha.vss.networking.server.sending;

import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds encoding work scheduled ahead of Netty's socket writability checks. */
public final class PlayerSendWindow {
    static final int MAX_PACKETS = 8;
    static final int MAX_BYTES = 512 * 1024;
    private int packets;
    private long bytes;

    public synchronized boolean hasRoom(int wireBytes) {
        return wireBytes > 0 && packets < MAX_PACKETS
                && (packets == 0 || bytes + wireBytes <= MAX_BYTES);
    }

    public synchronized Runnable acquire(int wireBytes) {
        if (!hasRoom(wireBytes)) return null;
        packets++;
        bytes += wireBytes;
        var completed = new AtomicBoolean();
        return () -> {
            if (!completed.compareAndSet(false, true)) return;
            synchronized (this) {
                packets--;
                bytes -= wireBytes;
            }
        };
    }

    public synchronized long pendingBytes() { return bytes; }
    public synchronized int pendingPackets() { return packets; }
}
