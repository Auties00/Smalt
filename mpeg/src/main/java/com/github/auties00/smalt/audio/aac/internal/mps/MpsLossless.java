/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_nlc_dec.c, and ecDataDec,
   coarse2fine, fine2coarse and getStrideMap of sac_bitdec.c). Copyright Agere Systems, Coding Technologies,
   Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Decodes the losslessly coded parameters of MPEG Surround ({@code EcData()} of ISO/IEC 23003-1 subclause 5.3.4,
 * with the Huffman and PCM coding of subclause 6.1.2).
 *
 * <p>Instances keep only scratch arrays; they are not thread-safe.
 */
public final class MpsLossless {
    public static final int CLD = 0;
    public static final int ICC = 1;
    public static final int CPC = 2;
    public static final int IPD = 3;

    public static final int MAX_BANDS = 28;
    public static final int MAX_SETS = 9;

    private static final int[] STRIDES = {1, 2, 5, 28};

    /**
     * Holds the per-frame coding modes of one parameter ({@code LOSSLESSDATA}).
     */
    public static final class Modes {
        public final int[] dataMode = new int[MAX_SETS];
        public final int[] quantCoarse = new int[MAX_SETS + 1];
        public final int[] freqResStride = new int[MAX_SETS + 1];
        public int quantCoarsePrevious;
        public final int[] uncompressedCoarse = new int[MAX_SETS + 1];
    }

    private final int[][] pair = new int[2][MAX_BANDS];
    private final int[][] difference = new int[2][MAX_BANDS];
    private final int[] historyMsb = new int[MAX_BANDS];
    private final int[] strides = new int[MAX_BANDS + 1];
    private final int[][] pairVector = new int[MAX_BANDS][2];
    private final int[][] escapes = new int[2][MAX_BANDS];
    private final int[] escapeIndex = new int[MAX_BANDS];
    private final int[] symbol = new int[2];

    /**
     * Reads the coded sets of one parameter of one frame ({@code ecDataDec}).
     *
     * @param reader the bit source
     * @param modes the coding modes, updated
     * @param data receives the coded sets, by set and band
     * @param history the previous values, by band, updated
     * @param type {@link #CLD}, {@link #ICC}, {@link #CPC} or {@link #IPD}
     * @param startBand the first band
     * @param stopBand the band past the last
     * @param defaultValue the value of an unspecified set
     * @param sets the number of parameter sets
     * @param residual whether residual coding is enabled
     * @param independent the frame's independency flag
     * @throws MediaException.Malformed if the data is invalid
     */
    public void read(AacBitReader reader, Modes modes, int[][] data, int[] history, int type, int startBand, int stopBand,
              int defaultValue, int sets, boolean residual, boolean independent) {
        for (var i = 0; i < sets; i++) {
            var mode = reader.read(2);
            modes.dataMode[i] = mode;
            if ((i == sets - 1 && mode == 2) || (residual && mode == 2)
                    || (i == 0 && independent && mode != 0 && mode != 3)) {
                throw new MediaException.Malformed("MPEG Surround parameter set uses data mode " + mode
                        + " where it is not allowed");
            }
        }
        var setIndex = 0;
        var paired = 0;
        var oldCoarse = modes.quantCoarsePrevious;
        for (var i = 0; i < sets; i++) {
            if (modes.dataMode[i] == 0) {
                for (var pb = startBand; pb < stopBand; pb++) {
                    history[pb] = defaultValue;
                }
                oldCoarse = 0;
            }
            if (modes.dataMode[i] != 3) {
                continue;
            }
            if (paired != 0) {
                paired = 0;
                continue;
            }
            paired = reader.read(1);
            var coarse = reader.read(1);
            var stride = reader.read(2);
            modes.quantCoarse[setIndex] = coarse;
            modes.freqResStride[setIndex] = stride;
            if (coarse != oldCoarse) {
                if (oldCoarse != 0) {
                    coarseToFine(history, type, startBand, stopBand - startBand);
                } else {
                    fineToCoarse(history, type, startBand, stopBand - startBand);
                }
            }
            var bands = strideMap(stride, startBand, stopBand, strides);
            for (var pb = 0; pb < bands; pb++) {
                history[startBand + pb] = history[strides[pb]];
            }
            decodePair(reader, data, history, type, setIndex, startBand, bands, paired != 0, coarse != 0,
                    independent && i == 0);
            for (var pb = 0; pb < bands; pb++) {
                var value = data[setIndex + paired][startBand + pb];
                if (type == IPD) {
                    value &= coarse != 0 ? 7 : 15;
                }
                for (var j = strides[pb]; j < strides[pb + 1]; j++) {
                    history[j] = value;
                }
            }
            oldCoarse = coarse;
            if (paired != 0) {
                modes.quantCoarse[setIndex + 1] = coarse;
                modes.freqResStride[setIndex + 1] = stride;
            }
            setIndex += paired + 1;
        }
    }

    /**
     * Doubles coarse indices into fine ones.
     *
     * @param data the indices
     * @param type the parameter type
     * @param start the first band
     * @param count the number of bands
     */
    public static void coarseToFine(int[] data, int type, int start, int count) {
        for (var i = start; i < start + count; i++) {
            data[i] <<= 1;
        }
        if (type == CLD) {
            for (var i = start; i < start + count; i++) {
                if (data[i] == -14) {
                    data[i] = -15;
                } else if (data[i] == 14) {
                    data[i] = 15;
                }
            }
        }
    }

    private static void fineToCoarse(int[] data, int type, int start, int count) {
        for (var i = start; i < start + count; i++) {
            if (type == CLD) {
                data[i] /= 2;
            } else {
                data[i] >>= 1;
            }
        }
    }

    /**
     * Builds the band borders of a frequency resolution stride ({@code getStrideMap}).
     *
     * @param stride the stride index
     * @param startBand the first band
     * @param stopBand the band past the last
     * @param out receives the borders, {@code bands + 1} values
     * @return the number of coded bands
     */
    public static int strideMap(int stride, int startBand, int stopBand, int[] out) {
        var step = STRIDES[stride];
        var bands = (stopBand - startBand - 1) / step + 1;
        out[0] = startBand;
        for (var pb = 1; pb <= bands; pb++) {
            out[pb] = out[pb - 1] + step;
        }
        var offset = 0;
        while (out[bands] > stopBand) {
            if (offset < bands) {
                offset++;
            } else {
                offset = 1;
            }
            for (var i = offset; i <= bands; i++) {
                out[i]--;
            }
        }
        return bands;
    }

    /**
     * {@return the stride of a frequency resolution stride index}
     *
     * @param index the stride index of the bitstream
     */
    public static int stride(int index) {
        return STRIDES[index];
    }

    private void decodePair(AacBitReader reader, int[][] out, int[] history, int type, int setIndex, int startBand,
                            int bands, boolean paired, boolean coarse, boolean independent) {
        var allowBackwards = !independent || setIndex > 0;
        var attachLsb = false;
        int levels;
        int offset;
        switch (type) {
            case CLD -> {
                levels = coarse ? 15 : 31;
                offset = coarse ? 7 : 15;
            }
            case ICC -> {
                levels = coarse ? 4 : 8;
                offset = 0;
            }
            case CPC -> {
                levels = coarse ? 26 : 51;
                offset = coarse ? 10 : 20;
                attachLsb = !coarse;
            }
            default -> {
                levels = coarse ? 8 : 16;
                offset = 0;
                attachLsb = !coarse;
            }
        }
        for (var i = 0; i < MAX_BANDS; i++) {
            pair[0][i] = 0;
            pair[1][i] = 0;
            difference[0][i] = 0;
            difference[1][i] = 0;
        }
        if (reader.readFlag()) {
            if (paired) {
                pcm(reader, pair[0], pair[1], offset, 2 * bands, levels);
            } else {
                pcm(reader, pair[0], null, offset, bands, levels);
            }
        } else {
            var diff0 = 0;
            var diff1 = 0;
            if (paired || allowBackwards) {
                diff0 = reader.read(1);
            }
            if (paired && (diff0 == 0 || allowBackwards)) {
                diff1 = reader.read(1);
            }
            var scheme = huffman(reader, difference[0], paired ? difference[1] : null, type, diff0, diff1, bands);
            var backwards = true;
            if (diff0 == 1 || diff1 == 1) {
                if (paired) {
                    if (diff0 == 1 && !allowBackwards) {
                        backwards = false;
                    } else if (diff1 == 1) {
                        backwards = true;
                    } else {
                        backwards = reader.read(1) == 0;
                    }
                }
            }
            var mixed = diff0 != diff1 && (scheme & 0xF) == 1;
            if (backwards) {
                if (diff0 == 0) {
                    frequencyDecode(difference[0], pair[0], bands);
                } else {
                    for (var i = 0; i < bands; i++) {
                        historyMsb[i] = history[i + startBand] + offset;
                        if (attachLsb) {
                            historyMsb[i] >>= 1;
                        }
                    }
                    timeDecode(historyMsb, difference[0], pair[0], mixed, bands, 1);
                }
                if (diff1 == 0) {
                    frequencyDecode(difference[1], pair[1], bands);
                } else {
                    timeDecode(pair[0], difference[1], pair[1], mixed, bands, 1);
                }
            } else {
                frequencyDecode(difference[1], pair[1], bands);
                if (diff0 == 0) {
                    frequencyDecode(difference[0], pair[0], bands);
                } else {
                    timeDecode(pair[1], difference[0], pair[0], mixed, bands, -1);
                }
            }
            attach(reader, pair[0], offset, attachLsb ? 1 : 0, bands);
            if (paired) {
                attach(reader, pair[1], offset, attachLsb ? 1 : 0, bands);
            }
        }
        System.arraycopy(pair[0], 0, out[setIndex], startBand, bands);
        if (paired) {
            System.arraycopy(pair[1], 0, out[setIndex + 1], startBand, bands);
        }
    }

    private static void frequencyDecode(int[] diff, int[] out, int count) {
        out[0] = diff[0];
        for (var i = 1; i < count; i++) {
            out[i] = out[i - 1] + diff[i];
        }
    }

    private static void timeDecode(int[] previous, int[] diff, int[] out, boolean mixed, int count, int sign) {
        var i = 0;
        if (mixed) {
            out[0] = diff[0];
            i = 1;
        }
        for (; i < count; i++) {
            out[i] = previous[i] + sign * diff[i];
        }
    }

    private static void attach(AacBitReader reader, int[] data, int offset, int lsbBits, int count) {
        for (var i = 0; i < count; i++) {
            var msb = data[i];
            if (lsbBits > 0) {
                data[i] = ((msb << lsbBits) | reader.read(lsbBits)) - offset;
            } else {
                data[i] = msb - offset;
            }
        }
    }

    private static void pcm(AacBitReader reader, int[] out1, int[] out2, int offset, int count, int levels) {
        int maxGroup = switch (levels) {
            case 3 -> 5;
            case 7 -> 6;
            case 11 -> 2;
            case 13, 19, 51 -> 4;
            case 25 -> 3;
            default -> 1;
        };
        var log = (float) (StrictMath.log((float) levels) / StrictMath.log(2.f));
        for (var i = 0; i < count; i += maxGroup) {
            var groupLength = Math.min(maxGroup, count - i);
            var chunk = (int) Math.ceil((float) groupLength * log);
            long value = readLong(reader, chunk);
            for (var j = 0; j < groupLength; j++) {
                var index = i + (groupLength - j - 1);
                var next = (int) (value % levels);
                if (out2 == null) {
                    out1[index] = next - offset;
                } else if (out1 == null) {
                    out2[index] = next - offset;
                } else if ((index & 1) != 0) {
                    out2[index / 2] = next - offset;
                } else {
                    out1[index / 2] = next - offset;
                }
                value = (value - next) / levels;
            }
        }
    }

    private static long readLong(AacBitReader reader, int bits) {
        long value = 0;
        while (bits > 0) {
            var take = Math.min(bits, 24);
            value = (value << take) | reader.read(take);
            bits -= take;
        }
        return value;
    }

    /**
     * Reads one Huffman codeword of a tree.
     *
     * @param reader the bit source
     * @param tree the Huffman tree, as nested node arrays
     * @return the leaf value, 0 or negative
     */
    public static int read(AacBitReader reader, int[] tree) {
        var node = 0;
        do {
            node = tree[2 * node + reader.readBit()];
        } while (node > 0);
        return node;
    }

    private static boolean read2D(AacBitReader reader, int[] tree, int[] out) {
        var node = read(reader, tree);
        if (node == 0) {
            out[0] = 0;
            out[1] = 1;
            return true;
        }
        var value = -(node + 1);
        out[0] = value >> 4;
        out[1] = value & 0xF;
        return false;
    }

    private static void restore(AacBitReader reader, int lav, int[] data, boolean ipd) {
        var sum = data[0] + data[1];
        var diff = data[0] - data[1];
        if (sum > lav) {
            data[0] = -sum + (2 * lav + 1);
            data[1] = -diff;
        } else {
            data[0] = sum;
            data[1] = diff;
        }
        if (!ipd && data[0] + data[1] != 0 && reader.readFlag()) {
            data[0] = -data[0];
            data[1] = -data[1];
        }
        if (data[0] - data[1] != 0 && reader.readFlag()) {
            var tmp = data[0];
            data[0] = data[1];
            data[1] = tmp;
        }
    }

    private static int[] firstTree(int type) {
        return switch (type) {
            case CLD -> MpsTables.CLD_FIRST;
            case ICC -> MpsTables.ICC_FIRST;
            case CPC -> MpsTables.CPC_FIRST;
            default -> MpsTables.IPD_FIRST;
        };
    }

    private static int[] tree1D(int type, int diff) {
        return switch (type) {
            case CLD -> MpsTables.CLD_1D[diff];
            case ICC -> MpsTables.ICC_1D[diff];
            case CPC -> MpsTables.CPC_1D[diff];
            default -> MpsTables.IPD_1D[diff];
        };
    }

    private static int[][] trees2D(int type, int diff, int pairing) {
        return switch (type) {
            case CLD -> MpsTables.CLD_2D[diff][pairing];
            case ICC -> MpsTables.ICC_2D[diff][pairing];
            case CPC -> MpsTables.CPC_2D[diff][pairing];
            default -> MpsTables.IPD_2D[diff][pairing];
        };
    }

    private static void decode1D(AacBitReader reader, int type, int diff, int[] out, int offset, int count,
                                 boolean first) {
        var start = 0;
        if (first) {
            out[offset] = -(read(reader, firstTree(type)) + 1);
            start = 1;
        }
        var tree = tree1D(type, diff);
        for (var i = start; i < count; i++) {
            var value = -(read(reader, tree) + 1);
            if (type != IPD && value != 0 && reader.readFlag()) {
                value = -value;
            }
            out[offset + i] = value;
        }
    }

    private void decode2D(AacBitReader reader, int type, int diff, int pairing, int column, int count, int stride,
                          int[] first0, int[] first1) {
        var lavIndex = -(read(reader, MpsTables.LAV) + 1);
        int lav;
        int treeIndex;
        switch (type) {
            case CLD -> {
                lav = 2 * lavIndex + 3;
                treeIndex = lavIndex;
            }
            case ICC -> {
                lav = 2 * lavIndex + 1;
                treeIndex = lavIndex;
            }
            case CPC -> {
                lav = 3 * lavIndex + 3;
                treeIndex = lavIndex;
            }
            default -> {
                treeIndex = lavIndex == 0 ? 3 : lavIndex - 1;
                lav = 2 * treeIndex + 1;
            }
        }
        var tree = firstTree(type);
        if (first0 != null) {
            first0[0] = -(read(reader, tree) + 1);
        }
        if (first1 != null) {
            first1[0] = -(read(reader, tree) + 1);
        }
        var nodes = trees2D(type, diff, pairing)[treeIndex];
        var escapesCount = 0;
        for (var i = 0; i < count; i += stride) {
            var target = pairVector[i + column];
            if (read2D(reader, nodes, target)) {
                escapeIndex[escapesCount++] = i + column;
            } else {
                restore(reader, lav, target, type == IPD);
            }
        }
        if (escapesCount > 0) {
            pcm(reader, escapes[0], escapes[1], 0, 2 * escapesCount, 2 * lav + 1);
            for (var i = 0; i < escapesCount; i++) {
                pairVector[escapeIndex[i]][0] = escapes[0][i] - lav;
                pairVector[escapeIndex[i]][1] = escapes[1][i] - lav;
            }
        }
    }

    private final int[] firstValue0 = new int[1];
    private final int[] firstValue1 = new int[1];

    private int huffman(AacBitReader reader, int[] out1, int[] out2, int type, int diff1, int diff2, int count) {
        var scheme = reader.read(1) << 4;
        if (scheme >> 4 == 1) {
            if (out1 != null && out2 != null) {
                scheme |= reader.read(1);
            }
        }
        if (scheme >> 4 == 0) {
            if (out1 != null) {
                decode1D(reader, type, diff1, out1, 0, count, diff1 == 0);
            }
            if (out2 != null) {
                decode1D(reader, type, diff2, out2, 0, count, diff2 == 0);
            }
            return scheme;
        }
        if ((scheme & 0xF) == 0) {
            var count1 = count;
            var count2 = count;
            var offset1 = 0;
            var offset2 = 0;
            var rest1 = false;
            var rest2 = false;
            var has1First = false;
            var has2First = false;
            if (out1 != null) {
                if (diff1 == 0) {
                    has1First = true;
                    count1 -= 1;
                    offset1 = 1;
                }
                rest1 = count1 % 2 != 0;
                if (rest1) {
                    count1 -= 1;
                }
            }
            if (out2 != null) {
                if (diff2 == 0) {
                    has2First = true;
                    count2 -= 1;
                    offset2 = 1;
                }
                rest2 = count2 % 2 != 0;
                if (rest2) {
                    count2 -= 1;
                }
            }
            if (out1 != null) {
                decode2D(reader, type, diff1, 0, 0, count1, 2, has1First ? firstValue0 : null, null);
                if (has1First) {
                    out1[0] = firstValue0[0];
                }
                if (rest1) {
                    decode1D(reader, type, diff1, out1, offset1 + count1, 1, false);
                }
            }
            if (out2 != null) {
                decode2D(reader, type, diff2, 0, 1, count2, 2, null, has2First ? firstValue1 : null);
                if (has2First) {
                    out2[0] = firstValue1[0];
                }
                if (rest2) {
                    decode1D(reader, type, diff2, out2, offset2 + count2, 1, false);
                }
            }
            if (out1 != null) {
                for (var i = 0; i < count1 - 1; i += 2) {
                    out1[offset1 + i] = pairVector[i][0];
                    out1[offset1 + i + 1] = pairVector[i][1];
                }
            }
            if (out2 != null) {
                for (var i = 0; i < count2 - 1; i += 2) {
                    out2[offset2 + i] = pairVector[i + 1][0];
                    out2[offset2 + i + 1] = pairVector[i + 1][1];
                }
            }
            return scheme;
        }
        var count1 = count;
        var offset = 0;
        var hasFirst = diff1 == 0 || diff2 == 0;
        if (hasFirst) {
            offset = 1;
            count1 -= 1;
        }
        var diff = diff1 == 1 || diff2 == 1 ? 1 : 0;
        decode2D(reader, type, diff, 1, 0, count1, 1, hasFirst ? firstValue0 : null, hasFirst ? firstValue1 : null);
        if (hasFirst) {
            out1[0] = firstValue0[0];
            out2[0] = firstValue1[0];
        }
        for (var i = 0; i < count1; i++) {
            out1[offset + i] = pairVector[i][0];
            out2[offset + i] = pairVector[i][1];
        }
        return scheme;
    }

    /**
     * Reads the run length coded envelope of guided envelope shaping ({@code huff_dec_reshape}).
     *
     * @param reader the bit source
     * @param out receives the values
     * @param count the number of values
     * @throws MediaException.Malformed if the runs exceed the count
     */
    public void readReshape(AacBitReader reader, int[] out, int count) {
        var received = 0;
        while (received < count) {
            read2D(reader, MpsTables.RESHAPE, symbol);
            var value = symbol[0];
            var length = symbol[1] + 1;
            if (received + length > out.length) {
                throw new MediaException.Malformed("MPEG Surround envelope runs exceed the frame");
            }
            for (var i = received; i < received + length; i++) {
                out[i] = value;
            }
            received += length;
        }
    }
}
