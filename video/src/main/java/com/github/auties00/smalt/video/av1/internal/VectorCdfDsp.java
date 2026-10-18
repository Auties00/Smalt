package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the symbol search and the CDF adaptation of {@link Msac#decodeSymbolAdapt} with the
 * Vector API: every entry of the CDF is searched and moves in one step instead of one loop
 * iteration per entry, whose exit depends on the decoded symbol and is hard to predict.
 *
 * <p>The class is loaded only when the Vector API is enabled; see {@link Msac}. Every Vector API
 * operation goes through the per-shape methods, constants are {@code static final} vectors, no
 * method takes or returns a vector, and every loaded or converted vector, and every vector
 * stored or shifted by a scalar, is first cast through the {@code static final} class of its
 * species; see {@link VectorMcDsp}.
 */
final class VectorCdfDsp {
    /**
     * The 16-lane short species of the CDF entries.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The probability scale, 32768, as a signed 16-bit lane.
     */
    private static final ShortVector SCALE = ShortVector.broadcast(S256, Short.MIN_VALUE);

    /**
     * For each count {@code n} from 0 to 15, 16 lanes of which the first {@code n} are all ones.
     */
    private static final short[] LANES_BELOW = lanesBelow();

    /**
     * For each lane {@code n} from 0 to 15, 16 lanes of which only lane {@code n} is all ones.
     */
    private static final short[] LANE_ONLY = laneOnly();

    /**
     * The minimum probability term subtracted per lane by {@link #symbol}, 4 times the lane index.
     */
    private static final ShortVector LANE_BIAS = ShortVector.fromArray(S256, laneBiasTable(), 0);

    /**
     * Prevents instantiation.
     */
    private VectorCdfDsp() {
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the entries of {@link #LANE_BIAS}.
     *
     * @return the table
     */
    private static short[] laneBiasTable() {
        var table = new short[16];
        for (var lane = 0; lane < 16; lane++) {
            table[lane] = (short) (4 * lane);
        }
        return table;
    }

    /**
     * Builds {@link #LANES_BELOW}.
     *
     * @return the table
     */
    private static short[] lanesBelow() {
        var table = new short[16 * 16];
        for (var n = 0; n < 16; n++) {
            for (var lane = 0; lane < n; lane++) {
                table[n * 16 + lane] = -1;
            }
        }
        return table;
    }

    /**
     * Builds {@link #LANE_ONLY}.
     *
     * @return the table
     */
    private static short[] laneOnly() {
        var table = new short[16 * 16];
        for (var n = 0; n < 16; n++) {
            table[n * 16 + n] = -1;
        }
        return table;
    }

    /**
     * Adapts a CDF after a symbol was decoded and advances its counter: the entries below the
     * symbol grow towards 32768 and the others, up to the number of entries, shrink towards 0,
     * both by a fraction of {@code 2^-rate} of the distance. The whole CDF is written back with
     * one store, so the next load of the same CDF reads it without a forwarding stall; the lanes
     * after the counter are written back unchanged. The lane masks come from tables, so only the
     * shifts, the masking and the addition depend on the loaded entries.
     *
     * @param cdf      the context, with at least 16 entries from {@code off}
     * @param off      the offset of the CDF
     * @param nSymbols the number of entries before the counter, from 1 to 15
     * @param val      the decoded symbol
     * @param rate     the adaptation rate
     * @param count    the counter after the update
     */
    static void update(char[] cdf, int off, int nSymbols, int val, int rate, int count) {
        var entries = SHORT256.cast(ShortVector.fromCharArray(S256, cdf, off));
        var raise = SHORT256.cast(SHORT256.cast(SCALE).lanewise(VectorOperators.SUB, entries))
                .lanewise(VectorOperators.LSHR, rate);
        var lower = entries.lanewise(VectorOperators.LSHR, rate);
        var below = SHORT256.cast(ShortVector.fromArray(S256, LANES_BELOW, val * 16));
        var active = SHORT256.cast(ShortVector.fromArray(S256, LANES_BELOW, nSymbols * 16));
        var counter = SHORT256.cast(ShortVector.fromArray(S256, LANE_ONLY, nSymbols * 16));
        var middle = active.lanewise(VectorOperators.XOR, below);
        var moved = entries.lanewise(VectorOperators.ADD, raise.lanewise(VectorOperators.AND, below))
                .lanewise(VectorOperators.SUB, lower.lanewise(VectorOperators.AND, middle));
        var next = SHORT256.cast(ShortVector.broadcast(S256, (short) count));
        var counted = moved.lanewise(VectorOperators.XOR, moved.lanewise(VectorOperators.XOR, next)
                .lanewise(VectorOperators.AND, counter));
        SHORT256.cast(counted).intoCharArray(cdf, off);
    }

    /**
     * Searches a CDF for the symbol a coded value selects and adapts the CDF, both from one load of
     * its entries. Every lane computes the upper bound of its symbol at once; the symbol is the
     * number of bounds above the coded value, which a population count of the comparison gives
     * without a data-dependent branch; the bounds of the symbol and of the symbol before it are
     * read back through {@code scratch}.
     *
     * @param cdf      the context, with at least 16 entries from {@code off}
     * @param off      the offset of the CDF
     * @param nSymbols the number of entries before the counter, from 1 to 15
     * @param c        the coded value, 16 bits
     * @param r        the range divided by 256
     * @param rng      the range
     * @param scratch  at least 17 entries of scratch space
     * @param adapt    whether to adapt the CDF
     * @return the symbol in bits 0 to 7, the upper bound of the symbol before it (the range for
     *         the first symbol) in bits 8 to 31 and the upper bound of the symbol in bits 32 and up
     */
    static long symbol(char[] cdf, int off, int nSymbols, int c, int r, int rng, char[] scratch, boolean adapt) {
        var entries = SHORT256.cast(ShortVector.fromCharArray(S256, cdf, off));
        var scaled = SHORT256.cast(entries.lanewise(VectorOperators.LSHR, 6));
        var half = SHORT256.cast(ShortVector.broadcast(S256, (short) (r >>> 1)));
        var odd = SHORT256.cast(ShortVector.broadcast(S256, (short) -(r & 1)));
        var floor = SHORT256.cast(ShortVector.broadcast(S256, (short) (4 * nSymbols)));
        var bounds = SHORT256.cast(scaled.lanewise(VectorOperators.MUL, half)
                .lanewise(VectorOperators.ADD, SHORT256.cast(scaled.lanewise(VectorOperators.AND, odd))
                        .lanewise(VectorOperators.LSHR, 1))
                .lanewise(VectorOperators.ADD, floor)
                .lanewise(VectorOperators.SUB, SHORT256.cast(LANE_BIAS)));
        var coded = SHORT256.cast(ShortVector.broadcast(S256, (short) c));
        var above = bounds.compare(VectorOperators.UGT, coded).toLong();
        var val = Long.bitCount(above & ((1L << nSymbols) - 1));
        bounds.intoCharArray(scratch, 1);
        scratch[0] = (char) rng;
        if (adapt) {
            int count = cdf[off + nSymbols];
            var rate = 4 + (count >> 4) + (nSymbols > 2 ? 1 : 0);
            var raise = SHORT256.cast(SHORT256.cast(SCALE).lanewise(VectorOperators.SUB, entries))
                    .lanewise(VectorOperators.LSHR, rate);
            var lower = entries.lanewise(VectorOperators.LSHR, rate);
            var below = SHORT256.cast(ShortVector.fromArray(S256, LANES_BELOW, val * 16));
            var active = SHORT256.cast(ShortVector.fromArray(S256, LANES_BELOW, nSymbols * 16));
            var counter = SHORT256.cast(ShortVector.fromArray(S256, LANE_ONLY, nSymbols * 16));
            var middle = active.lanewise(VectorOperators.XOR, below);
            var moved = entries.lanewise(VectorOperators.ADD, raise.lanewise(VectorOperators.AND, below))
                    .lanewise(VectorOperators.SUB, lower.lanewise(VectorOperators.AND, middle));
            var next = SHORT256.cast(ShortVector.broadcast(S256, (short) (count + (count < 32 ? 1 : 0))));
            var counted = moved.lanewise(VectorOperators.XOR, moved.lanewise(VectorOperators.XOR, next)
                    .lanewise(VectorOperators.AND, counter));
            SHORT256.cast(counted).intoCharArray(cdf, off);
        }
        return (long) scratch[val + 1] << 32 | (long) scratch[val] << 8 | val;
    }
}
