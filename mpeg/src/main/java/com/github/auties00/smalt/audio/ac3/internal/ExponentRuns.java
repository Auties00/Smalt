package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Holds the exponents of one exponent set as runs of equal exponents within each bit allocation
 * band, so that an encoder can sum a weight of the bit allocation pointer of every mantissa at many
 * SNR offsets without visiting every mantissa.
 *
 * <p>Every mantissa of a run shares its band's masking level and its exponent, so it receives the
 * same pointer (A/52 section 7.2.2.7), and {@link #tally(int[], int, int, long[])} returns exactly
 * the sum over the mantissas of the exponents the runs were collected from. Exponents shared by
 * groups of two or four mantissas (strategies D25 and D45) and the flat stretches of D15 exponents
 * collapse into a few runs per band. An instance is reused and not thread-safe.
 */
final class ExponentRuns {
    /**
     * The runs in mantissa order: the exponent in bits 0 to 7 and the length in the bits above.
     */
    private final int[] runs = new int[256];

    /**
     * The index into {@link #runs} after the last run of each band.
     */
    private final int[] bandEnds = new int[51];

    /**
     * The first band of the set.
     */
    private int firstBand;

    /**
     * One past the last band of the set, equal to {@link #firstBand} for an empty set.
     */
    private int lastBand;

    /**
     * Creates empty runs.
     */
    ExponentRuns() {
    }

    /**
     * Collects the runs of an exponent set.
     *
     * @param exponents the exponents, indexed by mantissa, each from 0 to 24
     * @param start     the first mantissa of the set
     * @param end       one past the last mantissa of the set
     */
    void collect(int[] exponents, int start, int end) {
        if (end <= start) {
            firstBand = 0;
            lastBand = 0;
            return;
        }
        var runs = this.runs;
        var ends = bandEnds;
        var run = 0;
        var bin = start;
        var band = Ac3Tables.MASK[start];
        firstBand = band;
        int last;
        do {
            last = Math.min(Ac3Tables.BAND_START[band + 1], end);
            while (bin < last) {
                var exponent = exponents[bin];
                var length = 1;
                bin++;
                while (bin < last && exponents[bin] == exponent) {
                    length++;
                    bin++;
                }
                runs[run++] = exponent | length << 8;
            }
            ends[band] = run;
            band++;
        } while (end > last);
        lastBand = band;
    }

    /**
     * Sums a weight of the bit allocation pointer of every mantissa of the set at an SNR offset:
     * {@code sum weights[address]} over the mantissas, where {@code address} is the index into
     * {@link Ac3Tables#BAP} the pointer is read from.
     *
     * @param mask      the masking curve, indexed by band
     * @param snrOffset the combined SNR offset {@code snroffset}
     * @param floor     the masking floor {@code floor}
     * @param padded    the weights, made by {@link #padCost(long[])}
     * @return the sum
     */
    long tally(int[] mask, int snrOffset, int floor, long[] padded) {
        var runs = this.runs;
        var ends = bandEnds;
        var sum = 0L;
        var run = 0;
        for (var band = firstBand; band < lastBand; band++) {
            var level = Math.max(0, mask[band] - snrOffset - floor);
            var bias = PAD + ((3072 - ((level & 0x1fe0) + floor)) >> 5);
            for (var bandEnd = ends[band]; run < bandEnd; run++) {
                var packed = runs[run];
                sum += padded[bias - ((packed & 0xFF) << 2)] * (packed >>> 8);
            }
        }
        return sum;
    }

    /**
     * The index of address zero in a table made by {@link #padCost(long[])}: the lowest address a
     * tally can reach, {@code -279}.
     */
    private static final int PAD = 279;

    /**
     * Pads a weight table indexed by the 64 table addresses so that it can be indexed by an
     * unclamped address from {@code -279} (offset by 279) to 160, each entry holding the weight of
     * the clamped address.
     *
     * @param weights the weight of each of the 64 table addresses
     * @return the padded table
     */
    static long[] padCost(long[] weights) {
        var padded = new long[PAD + 161];
        for (var i = 0; i < padded.length; i++) {
            padded[i] = weights[Math.min(63, Math.max(0, i - PAD))];
        }
        return padded;
    }

    /**
     * Sums a weight of the bit allocation pointer of every mantissa of an exponent set at an SNR
     * offset under two masking curves at once, without collecting runs: the same two sums as
     * {@link #tally(int[], int, int, long[])} over the runs of the set.
     *
     * @param exponents the exponents, indexed by mantissa, each from 0 to 24
     * @param start     the first mantissa of the set
     * @param end       one past the last mantissa of the set
     * @param maskA     the first masking curve, indexed by band
     * @param maskB     the second masking curve, indexed by band
     * @param snrOffset the combined SNR offset {@code snroffset}
     * @param floor     the masking floor {@code floor}
     * @param padded    the weights, made by {@link #padCost(long[])}
     * @param sums      receives the sum under {@code maskA} at index 0 and under {@code maskB} at 1
     */
    static void tallyPair(int[] exponents, int start, int end, int[] maskA, int[] maskB, int snrOffset, int floor,
                          long[] padded, long[] sums) {
        var sumA = 0L;
        var sumB = 0L;
        var common = 0L;
        var bin = start;
        var band = Ac3Tables.MASK[start];
        int last;
        do {
            last = Math.min(Ac3Tables.BAND_START[band + 1], end);
            var levelA = Math.max(0, maskA[band] - snrOffset - floor);
            var levelB = Math.max(0, maskB[band] - snrOffset - floor);
            var biasA = PAD + ((3072 - ((levelA & 0x1fe0) + floor)) >> 5);
            var biasB = PAD + ((3072 - ((levelB & 0x1fe0) + floor)) >> 5);
            if (biasA == biasB) {
                for (; bin < last; bin++) {
                    common += padded[biasA - (exponents[bin] << 2)];
                }
            } else {
                for (; bin < last; bin++) {
                    var shift = exponents[bin] << 2;
                    sumA += padded[biasA - shift];
                    sumB += padded[biasB - shift];
                }
            }
            band++;
        } while (end > last);
        sums[0] = common + sumA;
        sums[1] = common + sumB;
    }
}
