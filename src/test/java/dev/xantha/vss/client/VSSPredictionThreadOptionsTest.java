package dev.xantha.vss.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VSSPredictionThreadOptionsTest {
    enum Tier { LOW, MEDIUM, HIGH, CUSTOM }

    @Test void legacyPendingPairPresetsManualOverrideCancelAndApply() {
        var tier = new LegacyOption(Tier.MEDIUM);
        var threads = new LegacyOption(8);
        VSSPredictionThreadOptions.registerPair(tier, threads, 16);
        tier.setValue(Tier.LOW);
        assertEquals(4, threads.getValue());
        assertEquals(Tier.MEDIUM, tier.saved);
        assertEquals(8, threads.saved);
        threads.setValue(12);
        assertEquals(Tier.CUSTOM, tier.getValue());
        assertEquals(Tier.MEDIUM, tier.saved);
        tier.reset(); threads.reset();
        assertEquals(Tier.MEDIUM, tier.getValue());
        assertEquals(8, threads.getValue());
        tier.setValue(Tier.HIGH);
        assertEquals(14, threads.getValue());
        tier.apply(); threads.apply();
        assertEquals(Tier.HIGH, tier.saved);
        assertEquals(14, threads.saved);
        threads.setValue(16);
        assertEquals(Tier.CUSTOM, tier.getValue());
        tier.apply(); threads.apply();
        assertEquals(Tier.CUSTOM, tier.saved);
        assertEquals(16, threads.saved);
    }

    @Test void modernIntegerPairAndOtherOptionsRemainIndependent() {
        var tier = new ModernOption(1);
        var threads = new ModernOption(8);
        var other = new ModernOption(6);
        VSSPredictionThreadOptions.registerPair(tier, threads, 16);
        tier.modifyValue(0);
        assertEquals(4, threads.getValidatedValue());
        assertEquals(1, tier.saved);
        threads.modifyValue(12);
        assertEquals(3, tier.getValidatedValue());
        assertEquals(8, threads.saved);
        other.modifyValue(3);
        assertEquals(12, threads.getValidatedValue());
        tier.reset(); threads.reset();
        assertEquals(1, tier.getValidatedValue());
        assertEquals(8, threads.getValidatedValue());
        tier.modifyValue(2);
        assertEquals(14, threads.getValidatedValue());
        tier.apply(); threads.apply();
        assertEquals(2, tier.saved);
        assertEquals(14, threads.saved);
    }

    @Test void distinctScreensAndSingleProcessorBoundsRemainIndependent() {
        var a = new ModernOption(1); var b = new ModernOption(1);
        var c = new ModernOption(1); var d = new ModernOption(8);
        VSSPredictionThreadOptions.registerPair(a, b, 1);
        VSSPredictionThreadOptions.registerPair(c, d, 16);
        a.modifyValue(2);
        assertEquals(1, b.getValidatedValue());
        b.modifyValue(4);
        assertEquals(1, b.getValidatedValue());
        assertEquals(3, a.getValidatedValue());
        assertEquals(1, c.getValidatedValue());
        assertEquals(8, d.getValidatedValue());
    }

    public static final class LegacyOption {
        Object saved, pending;
        LegacyOption(Object value) { saved = pending = value; }
        public Object getValue() { return pending; }
        public void setValue(Object value) { pending = value; VSSPredictionThreadOptions.changed(this); }
        void reset() { pending = saved; }
        void apply() { saved = pending; }
    }

    public static final class ModernOption {
        Object saved, pending;
        ModernOption(Object value) { saved = pending = value; }
        public Object getValidatedValue() { return pending; }
        public void modifyValue(Object value) { pending = value; VSSPredictionThreadOptions.changed(this); }
        void reset() { pending = saved; }
        void apply() { saved = pending; }
    }
}
