package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds one short-term reference picture set, {@code st_ref_pic_set()} (ITU-T H.265 7.3.7 and
 * 7.4.8): the picture order count deltas of the pictures before ({@code S0}, closest first) and
 * after ({@code S1}, closest first) the current picture, and whether the current picture uses each
 * of them for reference.
 */
public final class ShortTermRps {
    /**
     * The most pictures a reference picture set holds, which is the largest decoded picture
     * buffer size.
     */
    public static final int MAX_PICTURES = 16;

    /**
     * The number of entries in {@link #deltaS0}.
     */
    public int numNegative;

    /**
     * The number of entries in {@link #deltaS1}.
     */
    public int numPositive;

    /**
     * The negative picture order count deltas, {@code DeltaPocS0}, in decreasing order.
     */
    public final int[] deltaS0 = new int[MAX_PICTURES];

    /**
     * Whether each {@link #deltaS0} picture is used by the current picture, {@code UsedByCurrPicS0}.
     */
    public final boolean[] usedS0 = new boolean[MAX_PICTURES];

    /**
     * The positive picture order count deltas, {@code DeltaPocS1}, in increasing order.
     */
    public final int[] deltaS1 = new int[MAX_PICTURES];

    /**
     * Whether each {@link #deltaS1} picture is used by the current picture, {@code UsedByCurrPicS1}.
     */
    public final boolean[] usedS1 = new boolean[MAX_PICTURES];

    /**
     * Constructs an empty set.
     */
    public ShortTermRps() {
    }

    /**
     * Returns the number of pictures in the set, {@code NumDeltaPocs}.
     *
     * @return the picture count
     */
    public int numDeltaPocs() {
        return numNegative + numPositive;
    }

    /**
     * Parses an {@code st_ref_pic_set(stRpsIdx)} structure.
     *
     * @param reader   the reader positioned at the structure
     * @param index    {@code stRpsIdx}: the index of the set among the SPS sets, or the number of
     *                 SPS sets for the set of a slice header
     * @param sets     the sets of the SPS parsed so far (at least {@code index} of them, unless
     *                 {@code index} is the number of sets)
     * @param numSets  {@code num_short_term_ref_pic_sets}
     * @param maxDpb   the most pictures the set may hold, at most {@link #MAX_PICTURES}
     * @return the parsed set
     * @throws MediaException.Malformed if a field is out of range
     */
    public static ShortTermRps parse(H264BitReader reader, int index, ShortTermRps[] sets, int numSets, int maxDpb) {
        var rps = new ShortTermRps();
        var predict = index != 0 && reader.readFlag();
        if (predict) {
            var deltaIdx = index == numSets ? reader.readUe(index - 1, "delta_idx_minus1") + 1 : 1;
            var reference = sets[index - deltaIdx];
            var sign = reader.readBit();
            var absDelta = reader.readUe(32767, "abs_delta_rps_minus1") + 1;
            var deltaRps = (1 - 2 * sign) * absDelta;
            var count = reference.numDeltaPocs();
            var used = new boolean[count + 1];
            var useDelta = new boolean[count + 1];
            for (var j = 0; j <= count; j++) {
                used[j] = reader.readFlag();
                useDelta[j] = used[j] || reader.readFlag();
            }
            var i = 0;
            for (var j = reference.numPositive - 1; j >= 0; j--) {
                var dPoc = reference.deltaS1[j] + deltaRps;
                if (dPoc < 0 && useDelta[reference.numNegative + j]) {
                    i = rps.addNegative(i, dPoc, used[reference.numNegative + j]);
                }
            }
            if (deltaRps < 0 && useDelta[count]) {
                i = rps.addNegative(i, deltaRps, used[count]);
            }
            for (var j = 0; j < reference.numNegative; j++) {
                var dPoc = reference.deltaS0[j] + deltaRps;
                if (dPoc < 0 && useDelta[j]) {
                    i = rps.addNegative(i, dPoc, used[j]);
                }
            }
            rps.numNegative = i;
            i = 0;
            for (var j = reference.numNegative - 1; j >= 0; j--) {
                var dPoc = reference.deltaS0[j] + deltaRps;
                if (dPoc > 0 && useDelta[j]) {
                    i = rps.addPositive(i, dPoc, used[j]);
                }
            }
            if (deltaRps > 0 && useDelta[count]) {
                i = rps.addPositive(i, deltaRps, used[count]);
            }
            for (var j = 0; j < reference.numPositive; j++) {
                var dPoc = reference.deltaS1[j] + deltaRps;
                if (dPoc > 0 && useDelta[reference.numNegative + j]) {
                    i = rps.addPositive(i, dPoc, used[reference.numNegative + j]);
                }
            }
            rps.numPositive = i;
        } else {
            rps.numNegative = reader.readUe(maxDpb, "num_negative_pics");
            rps.numPositive = reader.readUe(maxDpb - rps.numNegative, "num_positive_pics");
            var poc = 0;
            for (var i = 0; i < rps.numNegative; i++) {
                poc -= reader.readUe(32767, "delta_poc_s0_minus1") + 1;
                rps.deltaS0[i] = poc;
                rps.usedS0[i] = reader.readFlag();
            }
            poc = 0;
            for (var i = 0; i < rps.numPositive; i++) {
                poc += reader.readUe(32767, "delta_poc_s1_minus1") + 1;
                rps.deltaS1[i] = poc;
                rps.usedS1[i] = reader.readFlag();
            }
        }
        if (rps.numDeltaPocs() > maxDpb) {
            throw new MediaException.Malformed("short-term reference picture set larger than the decoded picture buffer");
        }
        return rps;
    }

    /**
     * Appends a negative delta.
     *
     * @param i    the number of negative deltas so far
     * @param dPoc the delta
     * @param used whether the current picture uses the picture
     * @return the new number of negative deltas
     * @throws MediaException.Malformed if the set overflows
     */
    private int addNegative(int i, int dPoc, boolean used) {
        if (i >= MAX_PICTURES) {
            throw new MediaException.Malformed("short-term reference picture set overflow");
        }
        deltaS0[i] = dPoc;
        usedS0[i] = used;
        return i + 1;
    }

    /**
     * Appends a positive delta.
     *
     * @param i    the number of positive deltas so far
     * @param dPoc the delta
     * @param used whether the current picture uses the picture
     * @return the new number of positive deltas
     * @throws MediaException.Malformed if the set overflows
     */
    private int addPositive(int i, int dPoc, boolean used) {
        if (i >= MAX_PICTURES) {
            throw new MediaException.Malformed("short-term reference picture set overflow");
        }
        deltaS1[i] = dPoc;
        usedS1[i] = used;
        return i + 1;
    }
}
