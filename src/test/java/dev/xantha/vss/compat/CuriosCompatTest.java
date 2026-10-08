package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class CuriosCompatTest {
    @Test
    void syncDataPreservesPerSlotRenderState() throws ReflectiveOperationException {
        FakeInventory source = new FakeInventory();
        source.getCurios().put("back", new FakeStacksHandler("youkai_bow", false));
        CompoundTag payload = CuriosCompat.captureHandler(source);

        FakeInventory target = new FakeInventory();
        target.getCurios().put("back", new FakeStacksHandler("", true));

        assertTrue(CuriosCompat.applyHandler(target, payload));
        FakeStacksHandler applied = (FakeStacksHandler) target.getCurios().get("back");
        assertEquals("youkai_bow", applied.item);
        assertFalse(applied.render);
    }

    @Test
    void captureTransfersSyncTagWithoutASecondDeepCopy() throws ReflectiveOperationException {
        FakeInventory source = new FakeInventory();
        FakeStacksHandler stacks = new FakeStacksHandler("amulet", true);
        source.getCurios().put("back", stacks);

        CompoundTag payload = CuriosCompat.captureHandler(source);
        CompoundTag encoded = payload.getList("Entries", Tag.TAG_COMPOUND).getCompound(0);

        assertSame(stacks.lastSyncTag, encoded.get("SyncTag"));
    }

    @Test
    void captureBoundsMalformedOrExpandedSlotMaps() throws ReflectiveOperationException {
        FakeInventory source = new FakeInventory();
        for (int i = 0; i < 80; i++) {
            source.getCurios().put("slot_" + i, new FakeStacksHandler("item_" + i, true));
        }

        CompoundTag payload = CuriosCompat.captureHandler(source);

        assertEquals(64, payload.getList("Entries", Tag.TAG_COMPOUND).size());
    }

    @Test
    void legacySerializationStillFallsBackToWriteAndReadTag() throws ReflectiveOperationException {
        FakeLegacyInventory source = new FakeLegacyInventory(37);
        CompoundTag payload = CuriosCompat.captureHandler(source);
        FakeLegacyInventory target = new FakeLegacyInventory(0);

        assertTrue(CuriosCompat.applyHandler(target, payload));
        assertEquals(37, target.value);
    }

    public static final class FakeInventory {
        private Map<String, Object> curios = new LinkedHashMap<>();

        public Map<String, Object> getCurios() {
            return curios;
        }

        public void setCurios(Map<String, Object> curios) {
            this.curios = curios;
        }
    }

    public static final class FakeStacksHandler {
        private String item;
        private boolean render;
        private CompoundTag lastSyncTag;

        private FakeStacksHandler(String item, boolean render) {
            this.item = item;
            this.render = render;
        }

        public CompoundTag getSyncTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Item", item);
            tag.putBoolean("Render", render);
            lastSyncTag = tag;
            return tag;
        }

        public void applySyncTag(CompoundTag tag) {
            item = tag.getString("Item");
            render = tag.getBoolean("Render");
        }
    }

    public static final class FakeLegacyInventory {
        private int value;

        private FakeLegacyInventory(int value) {
            this.value = value;
        }

        public CompoundTag writeTag() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Value", value);
            return tag;
        }

        public void readTag(Tag tag) {
            value = ((CompoundTag) tag).getInt("Value");
        }
    }
}
