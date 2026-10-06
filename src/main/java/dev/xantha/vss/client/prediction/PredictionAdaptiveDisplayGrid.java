package dev.xantha.vss.client.prediction;

import java.util.function.IntFunction;

/**
 * Small, conservative display-only heightfield approximation.
 *
 * <p>The caller supplies exact samples on demand.  The approximation is never
 * used for preview or interior columns, and it is rejected when any observed
 * metadata differs.  The fixed 0.5 block residual is deliberately conservative
 * for this first experiment; a later camera-aware budget can replace it without
 * changing the protected-field checks.</p>
 */
final class PredictionAdaptiveDisplayGrid {
    static final int AXIS = 8;
    private static final double MAX_HEIGHT_RESIDUAL = 0.5D;

    private PredictionAdaptiveDisplayGrid() {
    }

    /**
     * Resolves one regular 8x8 display patch.  Full patches at spacing four or
     * more probe the boundary, centre and four quadrant interiors.  A partial
     * patch or spacing below four is resolved point by point.
     */
    static ClientColumnSample[] sample(int stepBlocks, ClientColumnSample[] initial,
                                       IntFunction<ClientColumnSample> exact) {
        if (initial.length != AXIS * AXIS || stepBlocks < 4) {
            return exactAll(initial, exact);
        }

        ClientColumnSample[] result = initial.clone();
        int[] probes = probeIndices();
        for (int index : probes) {
            ClientColumnSample sample = result[index];
            if (needsExact(sample)) {
                sample = exact.apply(index);
                result[index] = sample;
            }
            if (!usable(sample)) {
                return exactAll(result, exact);
            }
        }

        ClientColumnSample template = result[probes[0]];
        for (int index : probes) {
            ClientColumnSample sample = result[index];
            if (!protectedFieldsMatch(template, sample)) {
                return exactAll(result, exact);
            }
            if (heightResidual(result, index) > MAX_HEIGHT_RESIDUAL) {
                return exactAll(result, exact);
            }
        }

        // Retained points are useful observations too.  If one belongs to a
        // different material/feature region, do not interpolate across it.
        for (int index = 0; index < result.length; index++) {
            ClientColumnSample sample = result[index];
            if (sample == null) {
                continue;
            }
            // A display result may have been produced by a different grid
            // alignment. Do not use its interpolated height as a new probe.
            if ((sample.flags() & ClientColumnSample.FLAG_DISPLAY) != 0) {
                result[index] = null;
                continue;
            }
            if (!usable(sample) || !protectedFieldsMatch(template, sample)
                    || heightResidual(result, index) > MAX_HEIGHT_RESIDUAL) {
                return exactAll(result, exact);
            }
        }

        for (int index = 0; index < result.length; index++) {
            if (result[index] == null) {
                int surface = (int) Math.round(interpolatedHeight(result, index));
                result[index] = displayCopy(template, surface);
            } else {
                result[index] = displayCopy(result[index], result[index].surfaceY());
            }
        }
        return result;
    }

    private static ClientColumnSample[] exactAll(ClientColumnSample[] initial,
                                                  IntFunction<ClientColumnSample> exact) {
        ClientColumnSample[] result = initial.clone();
        for (int index = 0; index < result.length; index++) {
            if (needsExact(result[index])) {
                result[index] = exact.apply(index);
            }
        }
        return result;
    }

    private static boolean needsExact(ClientColumnSample sample) {
        return sample == null || sample.approximate()
                || (sample.flags() & ClientColumnSample.FLAG_DISPLAY) != 0;
    }

    private static int[] probeIndices() {
        int[] probes = new int[36];
        int count = 0;
        for (int z = 0; z < AXIS; z++) {
            for (int x = 0; x < AXIS; x++) {
                boolean edge = x == 0 || x == AXIS - 1 || z == 0 || z == AXIS - 1;
                boolean centre = (x == 3 || x == 4) && (z == 3 || z == 4);
                boolean quadrant = (x == 2 || x == 5) && (z == 2 || z == 5);
                if (edge || centre || quadrant) {
                    probes[count++] = z * AXIS + x;
                }
            }
        }
        if (count != probes.length) {
            throw new AssertionError("adaptive display probe layout");
        }
        return probes;
    }

    private static boolean usable(ClientColumnSample sample) {
        // Preview records do not carry enough information for final display;
        // a complete underground volume also cannot be represented by a height.
        return sample != null && !sample.approximate() && !sample.captured()
                && (sample.flags() & ClientColumnSample.FLAG_DISPLAY) == 0
                && sample.volume() == null;
    }

    private static boolean protectedFieldsMatch(ClientColumnSample a, ClientColumnSample b) {
        if (a == null || b == null) {
            return false;
        }
        return a.fluid() == b.fluid()
                && a.flags() == b.flags()
                && a.biomeIndex() == b.biomeIndex()
                && a.topBlockIndex() == b.topBlockIndex()
                && a.structureIndex() == b.structureIndex()
                && a.treeKind() == b.treeKind()
                && a.treeDensity() == b.treeDensity()
                && a.treeHeight() == b.treeHeight()
                && a.groundFeatureKind() == b.groundFeatureKind()
                && a.underBlockIndex() == b.underBlockIndex()
                && a.deepBlockIndex() == b.deepBlockIndex()
                && a.surfaceBottom() == b.surfaceBottom()
                && a.lowerTop() == b.lowerTop()
                && a.lowerBottom() == b.lowerBottom()
                && a.spanFloor() == b.spanFloor()
                && (a.fluid() == 0 || a.fluidY() == b.fluidY());
    }

    private static double heightResidual(ClientColumnSample[] samples, int index) {
        ClientColumnSample sample = samples[index];
        if (sample == null) {
            return 0.0D;
        }
        return Math.abs(sample.surfaceY() - interpolatedHeight(samples, index));
    }

    private static double interpolatedHeight(ClientColumnSample[] samples, int index) {
        int x = index % AXIS;
        int z = index / AXIS;
        double tx = x / (double) (AXIS - 1);
        double tz = z / (double) (AXIS - 1);
        double topLeft = samples[0].surfaceY();
        double topRight = samples[AXIS - 1].surfaceY();
        double bottomLeft = samples[(AXIS - 1) * AXIS].surfaceY();
        double bottomRight = samples[AXIS * AXIS - 1].surfaceY();
        return topLeft * (1.0D - tx) * (1.0D - tz)
                + topRight * tx * (1.0D - tz)
                + bottomLeft * (1.0D - tx) * tz
                + bottomRight * tx * tz;
    }

    private static ClientColumnSample displayCopy(ClientColumnSample template, int surfaceY) {
        int fluidY = template.fluid() == 0 ? surfaceY : template.fluidY();
        return new ClientColumnSample(surfaceY, fluidY, template.biomeIndex(), template.topBlockIndex(),
                template.structureIndex(), template.treeKind(), template.treeDensity(), template.treeHeight(),
                template.fluid(), template.flags() | ClientColumnSample.FLAG_DISPLAY,
                template.groundFeatureKind(), template.underBlockIndex(), template.deepBlockIndex(),
                template.surfaceBottom(), template.lowerTop(), template.lowerBottom(), template.spanFloor(),
                null);
    }
}
