package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * The pulse position table of a bitrate scalable enhancement layer (ISO/IEC 14496-3 subclause 3.5.7.3.3.1), which
 * places the pulses of the layer on the tracks the core and the lower enhancement layers used least.
 *
 * <p>A subframe is split into ten tracks, the positions with the same remainder modulo 10 (Tables 3.99 and 3.100).
 * The pulses of the layer take the tracks with the fewest pulses of the lower layers, one track each, the lower track
 * first among equals.
 *
 * <p>Instances are not thread-safe.
 */
final class EnhancementTable {
    /**
     * The number of tracks.
     */
    private static final int TRACKS = 10;

    /**
     * The pulses of the core and of each enhancement layer.
     */
    private final int[] counts;

    /**
     * The positions of a track, {@code 2^bits} of them.
     */
    private final int bits;

    /**
     * The track of each pulse of the layer being decoded.
     */
    private final int[] tracks;

    /**
     * The pulses of the lower layers on each track.
     */
    private final int[] usage = new int[TRACKS];

    /**
     * Creates the table of the enhancement layers of a subframe size.
     *
     * @param subframeSize the samples of a subframe, 40 or 80
     * @param corePulses the pulses of the core
     * @param layerPulses the pulses of an enhancement layer
     */
    EnhancementTable(int subframeSize, int corePulses, int layerPulses) {
        this.bits = subframeSize == 80 ? 3 : 2;
        this.counts = new int[]{corePulses, layerPulses, layerPulses, layerPulses};
        this.tracks = new int[layerPulses];
    }

    /**
     * Decodes the pulses of an enhancement layer of a subframe into a vector.
     *
     * @param layer the enhancement layer, from 0
     * @param positionsOfLayers the pulse positions of the core (index 0) and of the lower enhancement layers
     * @param positionField {@code shape_enh_positions}
     * @param signField {@code shape_enh_signs}
     * @param out the vector, cleared first
     * @param positionsOut the position of each pulse of the layer
     */
    void decode(int layer, int[][] positionsOfLayers, int positionField, int signField, double[] out,
                int[] positionsOut) {
        Arrays.fill(usage, 0);
        for (var lower = 0; lower <= layer; lower++) {
            var positions = positionsOfLayers[lower];
            for (var p = 0; p < counts[lower]; p++) {
                usage[positions[p] % TRACKS]++;
            }
        }
        for (var i = 0; i < tracks.length; i++) {
            var best = 0;
            var least = Integer.MAX_VALUE;
            for (var t = 0; t < TRACKS; t++) {
                if (usage[t] < least) {
                    least = usage[t];
                    best = t;
                }
            }
            usage[best] = Integer.MAX_VALUE;
            tracks[i] = best;
        }
        Arrays.fill(out, 0);
        var count = tracks.length;
        var k = 0;
        for (var i = count - 1; i >= 0; i--) {
            var index = (positionField >>> k) & ((1 << bits) - 1);
            k += bits;
            var position = tracks[i] + TRACKS * index;
            out[position] = ((signField >>> (count - 1 - i)) & 1) == 1 ? -1 : 1;
            positionsOut[i] = position;
        }
    }
}
