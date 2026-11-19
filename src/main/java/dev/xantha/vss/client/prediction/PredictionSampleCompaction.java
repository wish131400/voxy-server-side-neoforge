package dev.xantha.vss.client.prediction;

import java.util.HashMap;

/** Tile-local, lossless interning of immutable samples; no global cache or decode cost. */
final class PredictionSampleCompaction {
    static long volumeBytes(ClientColumnSample[] samples) {
        return volumeBytes(samples,new ClientColumnSample[0]);
    }

    static long volumeBytes(ClientColumnSample[] samples, ClientColumnSample[] retained) {
        var unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<PredictionColumnVolume, Boolean>());
        long bytes = 0;
        for(var group:new ClientColumnSample[][]{samples,retained})
            for (var sample : group) if (sample != null && sample.volume() != null && unique.add(sample.volume()))
                bytes += sample.volume().bytes();
        return bytes;
    }

    static int compact(ClientColumnSample[] samples) {
        return compact(samples,new ClientColumnSample[0]);
    }

    static int compact(ClientColumnSample[] samples, ClientColumnSample[] retained) {
        var palette = new HashMap<ClientColumnSample, ClientColumnSample>();
        for(var group:new ClientColumnSample[][]{samples,retained})for (int i = 0; i < group.length; i++) {
            ClientColumnSample sample = group[i];
            if (sample == null) continue;
            ClientColumnSample previous = palette.putIfAbsent(sample, sample);
            if (previous != null) group[i] = previous;
        }
        return palette.size();
    }
}
