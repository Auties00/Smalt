package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * The pulse position tables of the multi-pulse excitation: the positions each pulse of a subframe may take, and the
 * number of bits its position index occupies (ISO/IEC 14496-3 subclause 3.5.7.2.3.4).
 *
 * <p>The positions of a subframe are split into tracks: every pulse owns the positions of one or more of the
 * {@code step} interleaved tracks, {@code 2^minBits} positions per track, where {@code minBits} is the fewest bits
 * any pulse uses. A pulse with {@code b} bits owns {@code 2^(b - minBits)} tracks, the first free one and then every
 * {@code step / 2^(b - minBits)} tracks onward, so that the pulses together own every position of the subframe once.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class PulseTable {
    /**
     * The number of position bits of each pulse.
     */
    final int[] bits;

    /**
     * The positions of each pulse, indexed by its position index.
     */
    final int[][] positions;

    /**
     * Builds the table of a subframe.
     *
     * @param subframeSize the samples of the subframe
     * @param bits the position bits of each pulse, in pulse order
     */
    PulseTable(int subframeSize, int[] bits) {
        this.bits = bits.clone();
        var minBits = Integer.MAX_VALUE;
        for (var b : bits) {
            minBits = Math.min(minBits, b);
        }
        var perTrack = 1 << minBits;
        var step = subframeSize / perTrack;
        var owner = new int[step];
        Arrays.fill(owner, -1);
        for (var pulse = 0; pulse < bits.length; pulse++) {
            var tracks = 1 << (bits[pulse] - minBits);
            var stride = (int) ((float) step / tracks + 0.5f);
            var track = 0;
            while (track < step && owner[track] >= 0) {
                track++;
            }
            for (var k = 0; k < tracks && track < step; k++) {
                owner[track] = pulse;
                track = (track + stride) % step;
                for (var guard = 0; guard < step && owner[track] >= 0; guard++) {
                    track = (track + 1) % step;
                }
            }
        }
        this.positions = new int[bits.length][];
        for (var pulse = 0; pulse < bits.length; pulse++) {
            var list = new int[1 << bits[pulse]];
            var l = 0;
            for (var track = 0; track < step; track++) {
                if (owner[track] == pulse) {
                    for (var j = 0; j < perTrack && l < list.length; j++) {
                        list[l++] = track + step * j;
                    }
                }
            }
            positions[pulse] = list;
        }
    }

    /**
     * Builds the table of a pulse configuration given by its total width.
     *
     * @param subframeSize the samples of the subframe
     * @param pulses the number of pulses
     * @param totalBits the width of the positions field
     * @return the table
     * @throws IllegalArgumentException if no allocation of the bits covers the subframe
     */
    static PulseTable of(int subframeSize, int pulses, int totalBits) {
        var bits = new int[pulses];
        if (!allocate(bits, 0, 16, totalBits, subframeSize)) {
            throw new IllegalArgumentException(pulses + " pulses of " + totalBits + " bits in " + subframeSize
                    + " samples");
        }
        return new PulseTable(subframeSize, bits);
    }

    /**
     * Finds the allocation, in non-increasing order, whose pulses own every position of the subframe once: the
     * position bits sum to the field width and the positions per pulse, {@code 2^bits}, sum to the subframe size.
     */
    private static boolean allocate(int[] bits, int pulse, int maxBits, int remainingBits, int remainingPositions) {
        if (pulse == bits.length) {
            return remainingBits == 0 && remainingPositions == 0;
        }
        for (var b = Math.min(maxBits, remainingBits); b >= 0; b--) {
            if ((1 << b) > remainingPositions) {
                continue;
            }
            bits[pulse] = b;
            if (allocate(bits, pulse + 1, b, remainingBits - b, remainingPositions - (1 << b))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes the pulses of a subframe into a vector: unit pulses at the positions the position field selects, with
     * the signs of the sign field.
     *
     * @param positionField the positions field, the index of the last pulse in its least significant bits
     * @param signField the signs field, the sign of the first pulse in its most significant bit; a set bit is negative
     * @param out the vector, cleared first, of the subframe size
     * @param positionsOut the position of each pulse, or {@code null}
     */
    void decode(long positionField, int signField, double[] out, int[] positionsOut) {
        Arrays.fill(out, 0);
        var count = bits.length;
        var k = 0;
        for (var i = count - 1; i >= 0; i--) {
            var index = (int) ((positionField >>> k) & ((1L << bits[i]) - 1));
            k += bits[i];
            var position = positions[i][index];
            out[position] = ((signField >>> (count - 1 - i)) & 1) == 1 ? -1 : 1;
            if (positionsOut != null) {
                positionsOut[i] = position;
            }
        }
    }
}
