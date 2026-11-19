package dev.xantha.vss.testutil;

import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import io.netty.buffer.Unpooled;
import java.util.stream.IntStream;
import net.minecraft.network.FriendlyByteBuf;

public final class HighWorldColumnFixtures {
    private HighWorldColumnFixtures() { }

    public static int[] heights(int count) {
        int min = count == 256 ? -128 : -16;
        return IntStream.range(min, min + count).toArray();
    }

    public static EncodedColumnData column(int count) {
        int[] heights = heights(count);
        int[] lengths = new int[count];
        var section = ColumnFilterFixtures.stone();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                int start = buffer.writerIndex();
                buffer.writeByte(heights[i]);
                section.write(buffer);
                buffer.writeBoolean(true).writeBytes(ColumnFilterFixtures.light(0x12));
                buffer.writeBoolean(true).writeBytes(ColumnFilterFixtures.light(0xEF));
                lengths[i] = buffer.writerIndex() - start;
            }
            byte[] raw = new byte[buffer.readableBytes()];
            buffer.readBytes(raw);
            var compressed = LodByteCompression.compressForNetwork(raw, false);
            return new EncodedColumnData(0, 7, compressed.method(), raw.length, compressed.bytes(),
                    123L, EncodedColumnData.SCHEMA_VERSION, true, heights, lengths,
                    EncodedColumnData.crc32c(compressed.bytes()));
        } finally { buffer.release(); }
    }
}
