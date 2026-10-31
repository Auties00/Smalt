/* Ports the DST module of the MPEG-4 Audio reference software (ISO/IEC 14496-5, Lossless coding of 1-bit
   oversampled audio - DST: dst_fram.c, unpack_dst.c, dst_data.c, dst_ac.c, ccp_calc.c, dst_init.c; developed by
   Aad Rijnberg, Fons Bruekers and Eric Knapen of Philips, edited by Richard Theelen of Philips and Maxim Anisiutkin of
   ICT Group, as copied in github.com/gmw/xld, XLDDSDDecoder/DST). ISO/IEC gives users of the MPEG-4 Audio standards
   free licence to this software module or modifications thereof for use in hardware or software products claiming
   conformance to the MPEG-4 Audio standards; copyright 2004. Modified for Smalt. */
package com.github.auties00.smalt.audio.dst;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes one DST frame (ISO/IEC 14496-3 subpart 10) into the interleaved, most significant bit first DSD of every
 * channel: the frame header (segmentation, mapping, prediction filters and probability tables) and the arithmetic
 * decoding of the prediction residuals.
 *
 * <p>Frames are coded independently. Instances are not thread-safe.
 */
final class DstFrameDecoder {
    private static final int SIZE_CODED_PRED_ORDER = 7;
    private static final int SIZE_PRED_COEF = 9;
    private static final int AC_BITS = 8;
    private static final int AC_PROBS = 1 << AC_BITS;
    private static final int AC_HIS_BITS = 6;
    private static final int AC_HIS_MAX = 1 << AC_HIS_BITS;
    private static final int AC_QSTEP = SIZE_PRED_COEF - AC_HIS_BITS;
    private static final int SIZE_RICE_METHOD = 2;
    private static final int SIZE_RICE_M = 3;
    private static final int MAX_FSEGS = 4;
    private static final int MAX_PSEGS = 8;
    private static final int MIN_FSEG_LEN = 1024;
    private static final int MIN_PSEG_LEN = 32;
    private static final int MAX_SEGS = 8;

    private static final int PBITS = AC_BITS;
    private static final int ABITS = PBITS + 4;
    private static final int ONE = 1 << ABITS;
    private static final int HALF = 1 << (ABITS - 1);

    private static final int[] FILTER_PRED_ORDER = {1, 2, 3};
    private static final int[][] FILTER_PRED_COEF = {{-8, 0, 0}, {-16, 8, 0}, {-9, -5, 6}};
    private static final int[] PTABLE_PRED_ORDER = {1, 2, 3};
    private static final int[][] PTABLE_PRED_COEF = {{-8, 0, 0}, {-16, 8, 0}, {-24, 24, -8}};

    private static final int[] REVERSE = {
            1, 65, 33, 97, 17, 81, 49, 113, 9, 73, 41, 105, 25, 89, 57, 121,
            5, 69, 37, 101, 21, 85, 53, 117, 13, 77, 45, 109, 29, 93, 61, 125,
            3, 67, 35, 99, 19, 83, 51, 115, 11, 75, 43, 107, 27, 91, 59, 123,
            7, 71, 39, 103, 23, 87, 55, 119, 15, 79, 47, 111, 31, 95, 63, 127,
            2, 66, 34, 98, 18, 82, 50, 114, 10, 74, 42, 106, 26, 90, 58, 122,
            6, 70, 38, 102, 22, 86, 54, 118, 14, 78, 46, 110, 30, 94, 62, 126,
            4, 68, 36, 100, 20, 84, 52, 116, 12, 76, 44, 108, 28, 92, 60, 124,
            8, 72, 40, 104, 24, 88, 56, 120, 16, 80, 48, 112, 32, 96, 64, 128};

    private final int channels;

    private final int frameBytes;

    private final int bitsPerChannel;

    private final int maxTables;

    /**
     * The segmentation of the filters and the probability tables over each channel's bits.
     */
    private static final class Segments {
        int resolution;
        final int[][] length;
        final int[] count;
        final int[][] table;

        Segments(int channels) {
            length = new int[channels][MAX_SEGS + 1];
            count = new int[channels];
            table = new int[channels][MAX_SEGS + 1];
        }
    }

    private final Segments filterSegments;

    private final Segments ptableSegments;

    private final byte[][] filterForBit;

    private final byte[][] ptableForBit;

    private final int[] predOrder;

    private final int[] ptableLength;

    private final short[][] coefficients;

    private final int[][] probabilities;

    private final short[][] filterTables;

    private final boolean[] halfProbability;

    private final int[] halfBits;

    private final long[] historyLow;

    private final long[] historyHigh;

    private int filters;

    private int ptables;

    private byte[] data = new byte[0];

    private int length;

    private long bit;

    private byte[] arithmetic = new byte[0];

    private int arithmeticLength;

    private int acA;

    private int acC;

    private int acPointer;

    /**
     * Constructs a frame decoder.
     *
     * @param channels the channel count, positive
     * @param frameBytes the bytes of DSD per channel in a frame, the DSD rate divided by 600
     */
    DstFrameDecoder(int channels, int frameBytes) {
        this.channels = channels;
        this.frameBytes = frameBytes;
        this.bitsPerChannel = frameBytes * 8;
        this.maxTables = 2 * channels;
        this.filterSegments = new Segments(channels);
        this.ptableSegments = new Segments(channels);
        this.filterForBit = new byte[channels][bitsPerChannel];
        this.ptableForBit = new byte[channels][bitsPerChannel];
        this.predOrder = new int[maxTables];
        this.ptableLength = new int[maxTables];
        this.coefficients = new short[maxTables][1 << SIZE_CODED_PRED_ORDER];
        this.probabilities = new int[maxTables][AC_HIS_MAX];
        this.filterTables = new short[maxTables][16 * 256];
        this.halfProbability = new boolean[channels];
        this.halfBits = new int[channels];
        this.historyLow = new long[channels];
        this.historyHigh = new long[channels];
    }

    /**
     * {@return the bytes of DSD every frame decodes to, all channels}
     */
    int outputBytes() {
        return frameBytes * channels;
    }

    private int bits(int count) {
        if (count == 0) {
            return 0;
        }
        if (bit + count > (long) length * 8) {
            throw new MediaException.Malformed("DST frame overread");
        }
        var value = 0;
        for (var i = 0; i < count; i++) {
            var index = (int) (bit >>> 3);
            value = value << 1 | ((data[index] >>> (7 - (int) (bit & 7))) & 1);
            bit++;
        }
        return value;
    }

    private int signedBits(int count) {
        var value = bits(count);
        if (value >= 1 << (count - 1)) {
            value -= 1 << count;
        }
        return value;
    }

    private static int log2RoundUp(long x) {
        var y = 0;
        while (x >= 1L << y) {
            y++;
        }
        return y;
    }

    private int rice(int m) {
        var run = 0;
        while (bits(1) == 0) {
            run++;
        }
        var number = (run << m) + bits(m);
        if (number != 0 && bits(1) == 1) {
            number = -number;
        }
        return number;
    }

    /**
     * Decodes a frame into {@code output}, from index {@code offset}, as {@link #outputBytes()} bytes.
     *
     * @throws MediaException.Malformed if the frame is corrupt
     */
    void decode(byte[] frame, int frameOffset, int frameLength, byte[] output, int offset) {
        if (data.length < frameLength) {
            data = new byte[frameLength];
        }
        System.arraycopy(frame, frameOffset, data, 0, frameLength);
        length = frameLength;
        bit = 0;
        var coded = bits(1);
        if (coded == 0) {
            bits(1);
            if (bits(6) != 0) {
                throw new MediaException.Malformed("illegal DST stuffing pattern");
            }
            var bytes = frameBytes * channels;
            if (length - 1 < bytes) {
                throw new MediaException.Malformed("DST frame of plain DSD of " + (length - 1) + " bytes");
            }
            System.arraycopy(data, 1, output, offset, bytes);
            return;
        }
        readSegments();
        readMapping();
        readFilters();
        readProbabilities();
        arithmeticLength = (int) ((long) length * 8 - bit);
        if (arithmetic.length < arithmeticLength) {
            arithmetic = new byte[Math.max(arithmeticLength, 1)];
        }
        for (var j = 0; j < arithmeticLength; j++) {
            arithmetic[j] = (byte) bits(1);
        }
        if (arithmeticLength > 0 && arithmetic[0] != 0) {
            throw new MediaException.Malformed("illegal DST arithmetic code");
        }
        fill(filterSegments, filterForBit);
        fill(ptableSegments, ptableForBit);
        buildFilterTables();
        reconstruct(output, offset);
    }

    private void readSegments() {
        var sameAsFilters = bits(1) == 1;
        readSegments(filterSegments, MAX_FSEGS, MIN_FSEG_LEN);
        if (sameAsFilters) {
            ptableSegments.resolution = filterSegments.resolution;
            for (var c = 0; c < channels; c++) {
                var count = filterSegments.count[c];
                if (count > MAX_PSEGS) {
                    throw new MediaException.Malformed("too many DST segments");
                }
                ptableSegments.count[c] = count;
                for (var s = 0; s < count; s++) {
                    var segment = filterSegments.length[c][s];
                    ptableSegments.length[c][s] = segment;
                    if (segment != 0 && ptableSegments.resolution * 8 * segment < MIN_PSEG_LEN) {
                        throw new MediaException.Malformed("invalid DST segment length");
                    }
                }
            }
        } else {
            readSegments(ptableSegments, MAX_PSEGS, MIN_PSEG_LEN);
        }
    }

    private void readSegments(Segments segments, int maxSegments, int minLength) {
        var frameLength = frameBytes;
        var resolutionRead = false;
        var maxSize = frameLength - minLength / 8;
        var defined = 0;
        var segment = 0;
        if (bits(1) == 1) {
            while (bits(1) == 0) {
                if (segment >= maxSegments) {
                    throw new MediaException.Malformed("too many DST segments for a channel");
                }
                if (!resolutionRead) {
                    segments.resolution = bits(log2RoundUp(frameLength - minLength / 8));
                    if (segments.resolution == 0 || segments.resolution > frameLength - minLength / 8) {
                        throw new MediaException.Malformed("invalid DST segment resolution");
                    }
                    resolutionRead = true;
                }
                var value = bits(log2RoundUp(maxSize / segments.resolution));
                if (segments.resolution * 8 * value < minLength
                        || segments.resolution * 8 * value > frameLength * 8 - defined - minLength) {
                    throw new MediaException.Malformed("invalid DST segment length");
                }
                segments.length[0][segment] = value;
                defined += segments.resolution * 8 * value;
                maxSize -= segments.resolution * value;
                segment++;
            }
            segments.count[0] = segment + 1;
            segments.length[0][segment] = 0;
            for (var c = 1; c < channels; c++) {
                segments.count[c] = segments.count[0];
                System.arraycopy(segments.length[0], 0, segments.length[c], 0, segments.count[0]);
            }
        } else {
            var c = 0;
            while (c < channels) {
                if (segment >= maxSegments) {
                    throw new MediaException.Malformed("too many DST segments for a channel");
                }
                if (bits(1) == 0) {
                    if (!resolutionRead) {
                        segments.resolution = bits(log2RoundUp(frameLength - minLength / 8));
                        if (segments.resolution == 0 || segments.resolution > frameLength - minLength / 8) {
                            throw new MediaException.Malformed("invalid DST segment resolution");
                        }
                        resolutionRead = true;
                    }
                    var value = bits(log2RoundUp(maxSize / segments.resolution));
                    if (segments.resolution * 8 * value < minLength
                            || segments.resolution * 8 * value > frameLength * 8 - defined - minLength) {
                        throw new MediaException.Malformed("invalid DST segment length");
                    }
                    segments.length[c][segment] = value;
                    defined += segments.resolution * 8 * value;
                    maxSize -= segments.resolution * value;
                    segment++;
                } else {
                    segments.count[c] = segment + 1;
                    segments.length[c][segment] = 0;
                    segment = 0;
                    defined = 0;
                    maxSize = frameLength - minLength / 8;
                    c++;
                }
            }
        }
        if (!resolutionRead) {
            segments.resolution = 1;
        }
    }

    private void readMapping() {
        var sameAsFilters = bits(1) == 1;
        filters = readMapping(filterSegments, maxTables);
        if (sameAsFilters) {
            for (var c = 0; c < channels; c++) {
                if (ptableSegments.count[c] != filterSegments.count[c]) {
                    throw new MediaException.Malformed("DST filter and table segments differ");
                }
                System.arraycopy(filterSegments.table[c], 0, ptableSegments.table[c], 0, filterSegments.count[c]);
            }
            ptables = filters;
            if (ptables > maxTables) {
                throw new MediaException.Malformed("too many DST tables");
            }
        } else {
            ptables = readMapping(ptableSegments, maxTables);
        }
        for (var c = 0; c < channels; c++) {
            halfProbability[c] = bits(1) == 1;
        }
    }

    private int readMapping(Segments segments, int max) {
        var count = 1;
        segments.table[0][0] = 0;
        if (bits(1) == 1) {
            for (var s = 1; s < segments.count[0]; s++) {
                var value = bits(log2RoundUp(count));
                segments.table[0][s] = value;
                if (value == count) {
                    count++;
                } else if (value > count) {
                    throw new MediaException.Malformed("invalid DST table number");
                }
            }
            for (var c = 1; c < channels; c++) {
                if (segments.count[c] != segments.count[0]) {
                    throw new MediaException.Malformed("DST mapping cannot be shared by every channel");
                }
                System.arraycopy(segments.table[0], 0, segments.table[c], 0, segments.count[0]);
            }
        } else {
            for (var c = 0; c < channels; c++) {
                for (var s = 0; s < segments.count[c]; s++) {
                    if (c != 0 || s != 0) {
                        var value = bits(log2RoundUp(count));
                        segments.table[c][s] = value;
                        if (value == count) {
                            count++;
                        } else if (value > count) {
                            throw new MediaException.Malformed("invalid DST table number");
                        }
                    }
                }
            }
        }
        if (count > max) {
            throw new MediaException.Malformed("too many DST tables for a frame");
        }
        return count;
    }

    private void readFilters() {
        for (var f = 0; f < filters; f++) {
            predOrder[f] = bits(SIZE_CODED_PRED_ORDER) + 1;
            var coef = coefficients[f];
            int index;
            if (bits(1) == 0) {
                for (index = 0; index < predOrder[f]; index++) {
                    coef[index] = (short) signedBits(SIZE_PRED_COEF);
                }
            } else {
                var method = bits(SIZE_RICE_METHOD);
                if (method >= FILTER_PRED_ORDER.length || FILTER_PRED_ORDER[method] >= predOrder[f]) {
                    throw new MediaException.Malformed("invalid DST coefficient coding method");
                }
                var order = FILTER_PRED_ORDER[method];
                for (index = 0; index < order; index++) {
                    coef[index] = (short) signedBits(SIZE_PRED_COEF);
                }
                var m = bits(SIZE_RICE_M);
                for (; index < predOrder[f]; index++) {
                    var x = 0;
                    for (var tap = 0; tap < order; tap++) {
                        x += FILTER_PRED_COEF[method][tap] * coef[index - tap - 1];
                    }
                    var c = x >= 0 ? rice(m) - (x + 4) / 8 : rice(m) + (-x + 3) / 8;
                    if (c < -(1 << (SIZE_PRED_COEF - 1)) || c >= 1 << (SIZE_PRED_COEF - 1)) {
                        throw new MediaException.Malformed("DST filter coefficient out of range");
                    }
                    coef[index] = (short) c;
                }
            }
            Arrays.fill(coef, index, coef.length, (short) 0);
        }
        for (var c = 0; c < channels; c++) {
            halfBits[c] = predOrder[filterSegments.table[c][0]];
        }
    }

    private void readProbabilities() {
        for (var p = 0; p < ptables; p++) {
            ptableLength[p] = bits(AC_HIS_BITS) + 1;
            var table = probabilities[p];
            if (ptableLength[p] > 1) {
                if (bits(1) == 0) {
                    for (var e = 0; e < ptableLength[p]; e++) {
                        table[e] = bits(AC_BITS - 1) + 1;
                    }
                } else {
                    var method = bits(SIZE_RICE_METHOD);
                    if (method >= PTABLE_PRED_ORDER.length || PTABLE_PRED_ORDER[method] >= ptableLength[p]) {
                        throw new MediaException.Malformed("invalid DST probability table coding method");
                    }
                    var order = PTABLE_PRED_ORDER[method];
                    for (var e = 0; e < order; e++) {
                        table[e] = bits(AC_BITS - 1) + 1;
                    }
                    var m = bits(SIZE_RICE_M);
                    for (var e = order; e < ptableLength[p]; e++) {
                        var x = 0;
                        for (var tap = 0; tap < order; tap++) {
                            x += PTABLE_PRED_COEF[method][tap] * table[e - tap - 1];
                        }
                        var c = x >= 0 ? rice(m) - (x + 4) / 8 : rice(m) + (-x + 3) / 8;
                        if (c < 1 || c > 1 << (AC_BITS - 1)) {
                            throw new MediaException.Malformed("DST probability table entry out of range");
                        }
                        table[e] = c;
                    }
                }
            } else {
                table[0] = 128;
            }
        }
    }

    private void fill(Segments segments, byte[][] forBit) {
        for (var c = 0; c < channels; c++) {
            var line = forBit[c];
            var start = 0;
            var s = 0;
            for (; s < segments.count[c] - 1; s++) {
                var end = start + segments.resolution * 8 * segments.length[c][s];
                if (end > bitsPerChannel) {
                    throw new MediaException.Malformed("DST segments past the end of the frame");
                }
                Arrays.fill(line, start, end, (byte) segments.table[c][s]);
                start = end;
            }
            Arrays.fill(line, start, bitsPerChannel, (byte) segments.table[c][s]);
        }
    }

    private void buildFilterTables() {
        for (var f = 0; f < filters; f++) {
            var table = filterTables[f];
            var order = predOrder[f];
            var coef = coefficients[f];
            for (var t = 0; t < 16; t++) {
                var k = Math.max(0, Math.min(8, order - t * 8));
                for (var i = 0; i < 256; i++) {
                    var value = 0;
                    for (var j = 0; j < k; j++) {
                        value += (((i >> j) & 1) * 2 - 1) * coef[t * 8 + j];
                    }
                    table[t * 256 + i] = (short) value;
                }
            }
        }
    }

    private void acInit() {
        acA = ONE - 1;
        acC = 0;
        for (acPointer = 1; acPointer <= ABITS; acPointer++) {
            acC <<= 1;
            if (acPointer < arithmeticLength) {
                acC |= arithmetic[acPointer];
            }
        }
    }

    private int acDecode(int p) {
        var ap = ((acA >>> PBITS) | ((acA >>> (PBITS - 1)) & 1)) * p;
        var h = acA - ap;
        int b;
        if (Integer.compareUnsigned(acC, h) >= 0) {
            b = 0;
            acC -= h;
            acA = ap;
        } else {
            b = 1;
            acA = h;
        }
        while (Integer.compareUnsigned(acA, HALF) < 0) {
            acA <<= 1;
            acC <<= 1;
            if (acPointer < arithmeticLength) {
                acC |= arithmetic[acPointer];
            }
            acPointer++;
        }
        return b;
    }

    private void reconstruct(byte[] output, int offset) {
        acInit();
        acDecode(REVERSE[(coefficients[0][0] + (1 << SIZE_PRED_COEF)) & 127]);
        Arrays.fill(output, offset, offset + frameBytes * channels, (byte) 0);
        Arrays.fill(historyLow, 0xAAAAAAAAAAAAAAAAL);
        Arrays.fill(historyHigh, 0xAAAAAAAAAAAAAAAAL);
        for (var bitNr = 0; bitNr < bitsPerChannel; bitNr++) {
            var byteNr = bitNr >>> 3;
            for (var c = 0; c < channels; c++) {
                var filter = filterForBit[c][bitNr] & 0xFF;
                if (filter >= filters) {
                    throw new MediaException.Malformed("DST segment maps an undefined filter");
                }
                var table = filterTables[filter];
                var tables = (predOrder[filter] + 7) >>> 3;
                var low = historyLow[c];
                var high = historyHigh[c];
                var predict = 0;
                for (var t = 0; t < tables; t++) {
                    var history = t < 8 ? low >>> (8 * t) : high >>> (8 * (t - 8));
                    predict += table[t * 256 + (int) (history & 0xFF)];
                }
                var prediction = (short) predict;
                int residual;
                if (halfProbability[c] && bitNr < halfBits[c]) {
                    residual = acDecode(AC_PROBS / 2);
                } else {
                    var ptable = ptableForBit[c][bitNr] & 0xFF;
                    if (ptable >= ptables) {
                        throw new MediaException.Malformed("DST segment maps an undefined probability table");
                    }
                    var index = Math.abs((int) prediction) >> AC_QSTEP;
                    if (index >= ptableLength[ptable]) {
                        index = ptableLength[ptable] - 1;
                    }
                    residual = acDecode(probabilities[ptable][index]);
                }
                var value = (((prediction & 0xFFFF) >>> 15) ^ residual) & 1;
                output[offset + byteNr * channels + c] |= (byte) (value << (7 - (bitNr & 7)));
                historyHigh[c] = (high << 1) | (low >>> 63);
                historyLow[c] = (low << 1) | value;
            }
        }
        if (acPointer < arithmeticLength - 7) {
            throw new MediaException.Malformed("DST arithmetic decoding error");
        }
    }
}
