package dev.xantha.vss.client.prediction;

import java.util.HashMap;

/** Tile-local, lossless interning of immutable samples; no global cache or decode cost. */
final class PredictionSampleCompaction {
    static int compact(ClientColumnSample[] samples) {
        var palette = new HashMap<ClientColumnSample, ClientColumnSample>();
        for (int i = 0; i < samples.length; i++) {
            ClientColumnSample sample = samples[i];
            if (sample == null) continue;
            ClientColumnSample previous = palette.putIfAbsent(sample, sample);
            if (previous != null) samples[i] = previous;
        }
        return palette.size();
    }
}
