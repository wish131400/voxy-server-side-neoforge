package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ServerIdentityS2CPayloadTest {
    @Test
    void roundTripPreservesStorageIdentity() {
        ServerIdentityS2CPayload original = new ServerIdentityS2CPayload("7K4M9PXA", false, "node-a");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerIdentityS2CPayload.encode(original, buffer);
            ServerIdentityS2CPayload decoded = ServerIdentityS2CPayload.decode(buffer);

            assertEquals("7K4M9PXA", decoded.serverIdentity());
            assertFalse(decoded.sharedWorld());
            assertEquals("node-a", decoded.nodeIdentity());
        } finally {
            buffer.release();
        }
    }
}
