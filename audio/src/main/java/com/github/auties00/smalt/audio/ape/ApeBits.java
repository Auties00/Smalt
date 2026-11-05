/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/UnBitArrayBase.cpp, UnBitArray.cpp, Old/UnBitArrayOld.cpp).
   Copyright (c) 2000 - 2026 Matthew T. Ashland; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.ape;

import com.github.auties00.smalt.MediaException;

/**
 * Reads the entropy-coded values of one Monkey's Audio frame: the bit array of little-endian 32-bit words read from
 * the most significant bit, the range coder of versions 3.891 to 3.989 and 3.990 onward, and the Rice codes of
 * earlier versions.
 *
 * <p>Instances are not thread-safe.
 */
final class ApeBits {
    private static final long TOP_VALUE = 1L << 31;

    private static final long BOTTOM_VALUE = TOP_VALUE >>> 8;

    private static final int EXTRA_BITS = 7;

    private static final int MODEL_ELEMENTS = 64;

    private static final int OVERFLOW_SIGNAL = 1;

    private static final int OVERFLOW_PIVOT_VALUE = 32768;

    private static final int[] RANGE_TOTAL_1 = {0, 14824, 28224, 39348, 47855, 53994, 58171, 60926, 62682, 63786,
            64463, 64878, 65126, 65276, 65365, 65419, 65450, 65469, 65480, 65487, 65491, 65493, 65494, 65495, 65496,
            65497, 65498, 65499, 65500, 65501, 65502, 65503, 65504, 65505, 65506, 65507, 65508, 65509, 65510, 65511,
            65512, 65513, 65514, 65515, 65516, 65517, 65518, 65519, 65520, 65521, 65522, 65523, 65524, 65525, 65526,
            65527, 65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536};

    private static final int[] RANGE_WIDTH_1 = {14824, 13400, 11124, 8507, 6139, 4177, 2755, 1756, 1104, 677, 415,
            248, 150, 89, 54, 31, 19, 11, 7, 4, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private static final int[] RANGE_TOTAL_2 = {0, 19578, 36160, 48417, 56323, 60899, 63265, 64435, 64971, 65232,
            65351, 65416, 65447, 65466, 65476, 65482, 65485, 65488, 65490, 65491, 65492, 65493, 65494, 65495, 65496,
            65497, 65498, 65499, 65500, 65501, 65502, 65503, 65504, 65505, 65506, 65507, 65508, 65509, 65510, 65511,
            65512, 65513, 65514, 65515, 65516, 65517, 65518, 65519, 65520, 65521, 65522, 65523, 65524, 65525, 65526,
            65527, 65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536};

    private static final int[] RANGE_WIDTH_2 = {19578, 16582, 12257, 7906, 4576, 2366, 1170, 536, 261, 119, 65, 31,
            19, 10, 6, 3, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private static final byte[] OVERFLOW_1 = overflowTable(RANGE_TOTAL_1);

    private static final byte[] OVERFLOW_2 = overflowTable(RANGE_TOTAL_2);

    private static final long[] K_SUM_MIN_BOUNDARY = {0, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768,
            65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728,
            268435456, 536870912, 1073741824, 2147483648L, 0, 0, 0, 0, 0};

    private static final long[] K_SUM_MAX_BOUNDARY = {32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768,
            65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728,
            268435456, 536870912, 1073741824, 2147483648L, 0, 0, 0, 0, 0, 0};

    private static final long[] K_SUM_MIN_BOUNDARY_OLD = {0, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768,
            65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728,
            268435456, 536870912, 1073741824, 2147483648L, 0, 0, 0, 0, 0, 0, 0};

    private static final long[] K_SUM_MAX_BOUNDARY_OLD = {128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536,
            131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432, 67108864, 134217728,
            268435456, 536870912, 1073741824, 2147483648L, 0, 0, 0, 0, 0, 0, 0, 0};

    private final int version;

    private int[] words = new int[0];

    /**
     * The number of bytes of frame data, beyond which no bit may be read.
     */
    private int goodBytes;

    /**
     * The absolute bit position, from the most significant bit of the first word.
     */
    private long bit;

    private long low;

    private long range;

    private long buffer;

    /**
     * The Rice parameter and running sum of the versions before 3.891.
     */
    private int riceK;

    private long riceSum;

    /**
     * The adaptive state of one channel's range-coded values ({@code UNBIT_ARRAY_STATE}).
     */
    static final class State {
        int k;

        long kSum;

        void flush() {
            k = 10;
            kSum = (1L << k) * 16;
        }
    }

    /**
     * Constructs a reader for a file version.
     *
     * @param version the file version times 1000
     */
    ApeBits(int version) {
        this.version = version;
    }

    private static byte[] overflowTable(int[] total) {
        var table = new byte[65536];
        var overflow = 0;
        for (var z = 0; z < 65536; z++) {
            if (z >= total[overflow + 1]) {
                overflow++;
            }
            table[z] = (byte) overflow;
        }
        return table;
    }

    /**
     * Loads the frame data of a packet: the words from byte {@code offset} on, positioned at bit {@code skipBits}.
     */
    void load(byte[] data, int offset, int length, long skipBits) {
        var count = (length + 3) / 4 + 2;
        if (words.length < count) {
            words = new int[Math.max(count, words.length * 2)];
        }
        var full = length / 4;
        for (var i = 0; i < full; i++) {
            var at = offset + 4 * i;
            words[i] = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                    | data[at + 3] << 24;
        }
        for (var i = full; i < count; i++) {
            var value = 0;
            for (var b = 0; b < 4; b++) {
                var at = 4 * i + b;
                if (at < length) {
                    value |= (data[offset + at] & 0xFF) << (8 * b);
                }
            }
            words[i] = value;
        }
        goodBytes = length;
        bit = skipBits;
    }

    /**
     * Rewinds to a bit position of the loaded data.
     */
    void seek(long position) {
        bit = position;
    }

    private void ensure(int bits) {
        if (bit + bits >= (long) goodBytes * 8) {
            throw new MediaException.Malformed("APE frame overread");
        }
    }

    private int word(long index) {
        if (index >= words.length) {
            throw new MediaException.Malformed("APE frame overread");
        }
        return words[(int) index];
    }

    /**
     * Reads an unsigned value of {@code bits} bits, from 0 to 32 ({@code DecodeValueXBits}).
     */
    long bits(int bits) {
        if (bits == 0) {
            return 0;
        }
        ensure(bits);
        var left = 32 - (int) (bit & 31);
        var index = bit >>> 5;
        bit += bits;
        var current = word(index) & 0xFFFFFFFFL;
        var masked = left == 32 ? current : current & ((1L << left) - 1);
        if (left >= bits) {
            return masked >>> (left - bits);
        }
        var right = bits - left;
        return ((masked << right) | ((word(index + 1) & 0xFFFFFFFFL) >>> (32 - right))) & 0xFFFFFFFFL;
    }

    private int nextByte() {
        if (bit + 8 >= (long) goodBytes * 8) {
            ensure(8);
        }
        var value = (word(bit >>> 5) >>> (24 - (int) (bit & 31))) & 0xFF;
        bit += 8;
        return value;
    }

    void advanceToByteBoundary() {
        var mod = (int) (bit % 8);
        if (mod != 0) {
            bit += 8 - mod;
        }
    }

    /**
     * Starts the range coder after the frame header ({@code FlushBitArray}).
     */
    void startRange() {
        advanceToByteBoundary();
        bits(8);
        buffer = bits(8);
        low = buffer >>> (8 - EXTRA_BITS);
        range = 1L << EXTRA_BITS;
    }

    /**
     * Ends the range coder ({@code Finalize}).
     */
    void finishRange() {
        while (range <= BOTTOM_VALUE) {
            bit += 8;
            range = (range << 8) & 0xFFFFFFFFL;
            if (range == 0) {
                return;
            }
        }
        if (version < 3990 && version <= 3950) {
            bit -= 16;
        }
    }

    private void normalize() {
        while (range <= BOTTOM_VALUE) {
            buffer = ((buffer << 8) | nextByte()) & 0xFFFFFFFFL;
            low = ((low << 8) | ((buffer >>> 1) & 0xFF)) & 0xFFFFFFFFL;
            range = (range << 8) & 0xFFFFFFFFL;
            if (range == 0) {
                return;
            }
        }
    }

    private long decodeFast(int shift) {
        while (range <= BOTTOM_VALUE) {
            buffer = ((buffer << 8) | nextByte()) & 0xFFFFFFFFL;
            low = ((low << 8) | ((buffer >>> 1) & 0xFF)) & 0xFFFFFFFFL;
            range = (range << 8) & 0xFFFFFFFFL;
            if (range == 0) {
                return 0;
            }
        }
        range >>>= shift;
        if (range == 0) {
            throw new MediaException.Malformed("APE range coder underflow");
        }
        return low / range;
    }

    private long decodeFastWithUpdate(int shift) {
        while (range <= BOTTOM_VALUE) {
            if (range == 0) {
                throw new MediaException.Malformed("APE range coder underflow");
            }
            buffer = ((buffer << 8) | nextByte()) & 0xFFFFFFFFL;
            low = ((low << 8) | ((buffer >>> 1) & 0xFF)) & 0xFFFFFFFFL;
            range = (range << 8) & 0xFFFFFFFFL;
        }
        range >>>= shift;
        if (range == 0) {
            throw new MediaException.Malformed("APE range coder underflow");
        }
        var result = low / range;
        low = low % range;
        return result;
    }

    private void updateState(State state, long value) {
        state.kSum = (state.kSum + ((value + 1) / 2) - ((state.kSum + 16) >>> 5)) & 0xFFFFFFFFL;
        if (state.k < 0 || state.k > 31) {
            throw new MediaException.Malformed("APE range coder state");
        }
        if (state.kSum < K_SUM_MIN_BOUNDARY[state.k]) {
            state.k--;
        } else if (K_SUM_MIN_BOUNDARY[state.k + 1] != 0 && state.kSum >= K_SUM_MIN_BOUNDARY[state.k + 1]) {
            state.k++;
        }
    }

    private static long signed(long value) {
        return (value & 1) != 0 ? (value >> 1) + 1 : -(value >> 1);
    }

    /**
     * Decodes a range-coded value of version 3.990 or later ({@code CUnBitArray::DecodeValueRange}).
     */
    long rangeValue(State state) {
        return version >= 3990 ? rangeValueCurrent(state) : rangeValue3891(state);
    }

    private long decodeOverflow(long[] pivot) {
        var total = decodeFast(16);
        if (total >= 65536) {
            throw new MediaException.Malformed("APE range coder overflow");
        }
        long overflow = OVERFLOW_2[(int) total];
        low = (low - range * RANGE_TOTAL_2[(int) overflow]) & 0xFFFFFFFFL;
        range = (range * RANGE_WIDTH_2[(int) overflow]) & 0xFFFFFFFFL;
        if (overflow == MODEL_ELEMENTS - 1) {
            overflow = decodeFastWithUpdate(16);
            overflow <<= 16;
            overflow |= decodeFastWithUpdate(16);
            overflow &= 0xFFFFFFFFL;
            if (overflow == OVERFLOW_SIGNAL) {
                pivot[0] = OVERFLOW_PIVOT_VALUE;
                return decodeOverflow(pivot);
            }
        }
        return overflow;
    }

    private final long[] pivotHolder = new long[1];

    private long rangeValueCurrent(State state) {
        pivotHolder[0] = Math.max(state.kSum / 32, 1);
        var overflow = decodeOverflow(pivotHolder);
        var pivot = pivotHolder[0];
        long base;
        if (pivot >= 1 << 16) {
            var pivotBits = 0;
            while ((pivot >>> pivotBits) > 0) {
                pivotBits++;
            }
            var shift = pivotBits >= 16 ? pivotBits - 16 : 0;
            var split = 1L << shift;
            var pivotA = pivot / split + 1;
            var pivotB = split;
            normalizeNoZeroCheck();
            range /= pivotA;
            if (range == 0) {
                throw new MediaException.Malformed("APE range coder underflow");
            }
            var baseA = low / range;
            low = low % range;
            normalizeNoZeroCheck();
            range /= pivotB;
            if (range == 0) {
                throw new MediaException.Malformed("APE range coder underflow");
            }
            var baseB = low / range;
            low = low % range;
            base = (baseA * split + baseB) & 0xFFFFFFFFL;
        } else {
            while (range <= BOTTOM_VALUE) {
                buffer = ((buffer << 8) | nextByte()) & 0xFFFFFFFFL;
                low = ((low << 8) | ((buffer >>> 1) & 0xFF)) & 0xFFFFFFFFL;
                range = (range << 8) & 0xFFFFFFFFL;
                if (range == 0) {
                    return 0;
                }
            }
            range /= pivot;
            if (range == 0) {
                throw new MediaException.Malformed("APE range coder underflow");
            }
            base = low / range;
            low = low % range;
        }
        var value = base + overflow * pivot;
        updateState(state, value);
        return signed(value);
    }

    private void normalizeNoZeroCheck() {
        var guard = 0;
        while (range <= BOTTOM_VALUE) {
            buffer = ((buffer << 8) | nextByte()) & 0xFFFFFFFFL;
            low = ((low << 8) | ((buffer >>> 1) & 0xFF)) & 0xFFFFFFFFL;
            range = (range << 8) & 0xFFFFFFFFL;
            if (++guard > 8) {
                throw new MediaException.Malformed("APE range coder underflow");
            }
        }
    }

    private long rangeValue3891(State state) {
        var total = decodeFast(16);
        if (total >= 65536) {
            throw new MediaException.Malformed("APE range coder overflow");
        }
        long overflow = OVERFLOW_1[(int) total];
        low = (low - range * RANGE_TOTAL_1[(int) overflow]) & 0xFFFFFFFFL;
        range = (range * RANGE_WIDTH_1[(int) overflow]) & 0xFFFFFFFFL;
        int tempK;
        if (overflow == MODEL_ELEMENTS - 1) {
            tempK = (int) decodeFastWithUpdate(5);
            overflow = 0;
        } else {
            tempK = state.k < 1 ? 0 : state.k - 1;
        }
        long value;
        if (tempK <= 16 || version < 3910) {
            if (tempK > 32) {
                throw new MediaException.Malformed("APE range coder state");
            }
            value = decodeFastWithUpdate(tempK);
        } else {
            if (tempK > 48) {
                throw new MediaException.Malformed("APE range coder state");
            }
            var x1 = decodeFastWithUpdate(16);
            var x2 = decodeFastWithUpdate(tempK - 16);
            value = x1 | (x2 << 16);
        }
        value += overflow << tempK;
        updateState(state, value);
        return signed(value);
    }

    /**
     * Fills {@code output} with a whole channel's range-coded values ({@code GenerateArrayRange}).
     */
    void rangeArray(int[] output, int count) {
        var state = new State();
        state.flush();
        startRange();
        for (var z = 0; z < count; z++) {
            output[z] = (int) rangeValue(state);
        }
        finishRange();
    }

    private int riceZeros() {
        var initial = bit;
        while ((word(bit >>> 5) & (0x80000000 >>> (int) (bit & 31))) == 0) {
            bit++;
        }
        bit++;
        var count = bit - initial - 1;
        if (count > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("APE Rice code too long");
        }
        return (int) count;
    }

    /**
     * Reads an unsigned Rice code of parameter {@code k} ({@code DecodeValueRiceUnsigned}).
     */
    long riceUnsigned(int k) {
        var zeros = riceZeros() & 0xFFFFFFFFL;
        if (k == 0) {
            return zeros;
        }
        return ((zeros << k) | bits(k)) & 0xFFFFFFFFL;
    }

    private static int getK(long x) {
        if (x == 0) {
            return 0;
        }
        var k = 0;
        do {
            k++;
        } while (k < 32 && x >= 1L << k);
        return k;
    }

    private long kBits(int k) {
        var index = bit >>> 5;
        var position = (int) (bit & 31);
        bit += k;
        var leftExtra = (32 - k) - position;
        var mask = position == 0 ? 0xFFFFFFFFL : (0xFFFFFFFFL >>> position);
        var left = word(index) & mask;
        if (leftExtra >= 0) {
            return left >>> leftExtra;
        }
        return ((left << -leftExtra) | ((word(index + 1) & 0xFFFFFFFFL) >>> (32 + leftExtra))) & 0xFFFFFFFFL;
    }

    /**
     * Fills {@code output} with a channel's Rice-coded values of the versions before 3.860
     * ({@code GenerateArrayOld}).
     */
    void riceArrayOld(int[] output, int count) {
        var max = Math.min(count, 5);
        for (var q = 0; q < max; q++) {
            output[q] = (int) riceUnsigned(10);
        }
        if (count <= 5) {
            unfold(output, 0, count);
            return;
        }
        var kSum = 0L;
        for (var q = 0; q < 5; q++) {
            kSum += output[q] & 0xFFFFFFFFL;
        }
        kSum &= 0xFFFFFFFFL;
        var k = getK(kSum / 10);
        max = Math.min(count, 64);
        for (var q = 5; q < max; q++) {
            output[q] = (int) riceUnsigned(k);
            kSum = (kSum + (output[q] & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
            k = getK(kSum / (q + 1) / 2);
        }
        if (count <= 64) {
            unfold(output, 0, count);
            return;
        }
        k = getK(kSum >>> 7);
        var kMin = K_SUM_MIN_BOUNDARY_OLD[k];
        var kMax = K_SUM_MAX_BOUNDARY_OLD[k];
        var p2 = 0;
        for (var p1 = 64; p1 < count; p1++, p2++) {
            long v = riceZeros() & 0xFFFFFFFFL;
            if (k != 0) {
                v = ((v << k) | kBits(k)) & 0xFFFFFFFFL;
            }
            output[p1] = (int) v;
            kSum = (kSum + (output[p1] - output[p2])) & 0xFFFFFFFFL;
            output[p2] = (output[p2] % 2) != 0 ? (output[p2] >> 1) + 1 : -(output[p2] >> 1);
            if (kSum < kMin || kSum >= kMax) {
                if (kSum < kMin) {
                    do {
                        k--;
                    } while (k > 0 && kSum < K_SUM_MIN_BOUNDARY_OLD[k]);
                } else {
                    while (K_SUM_MAX_BOUNDARY_OLD[k + 1] != 0) {
                        k++;
                        if (kSum < K_SUM_MAX_BOUNDARY_OLD[k]) {
                            break;
                        }
                    }
                }
                if (k < 0) {
                    throw new MediaException.Malformed("APE Rice state");
                }
                kMax = K_SUM_MAX_BOUNDARY_OLD[k];
                kMin = K_SUM_MIN_BOUNDARY_OLD[k];
            }
        }
        unfold(output, p2, count);
    }

    private static void unfold(int[] output, int from, int to) {
        for (var i = from; i < to; i++) {
            var value = output[i];
            output[i] = (value & 1) != 0 ? (value >> 1) + 1 : -(value >> 1);
        }
    }

    /**
     * Fills {@code output} with a channel's adaptive Rice-coded values of versions 3.860 to 3.890
     * ({@code GenerateArrayRice}).
     */
    void riceArray(int[] output, int count) {
        riceK = 10;
        riceSum = 1024 * 16;
        var cap = version > 3880;
        for (var i = 0; i < count; i++) {
            output[i] = riceValue(cap);
        }
    }

    private int riceValue(boolean capOverflow) {
        var overflow = riceZeros();
        if (capOverflow) {
            while (overflow >= 16) {
                riceK += 4;
                overflow -= 16;
            }
        }
        if (riceK > 31) {
            throw new MediaException.Malformed("APE Rice state");
        }
        long v;
        if (riceK != 0) {
            v = ((long) overflow << riceK) & 0xFFFFFFFFL;
            v |= kBits(riceK);
        } else {
            v = overflow & 0xFFFFFFFFL;
        }
        riceSum = (riceSum + v - ((riceSum + 8) >>> 4)) & 0xFFFFFFFFL;
        if (riceSum < K_SUM_MIN_BOUNDARY[riceK]) {
            riceK--;
        } else if (K_SUM_MAX_BOUNDARY[riceK] != 0 && riceSum >= K_SUM_MAX_BOUNDARY[riceK]) {
            riceK++;
        }
        if (riceK < 0) {
            throw new MediaException.Malformed("APE Rice state");
        }
        var half = (int) (v >>> 1);
        return (v & 1) != 0 ? half + 1 : -half;
    }
}
