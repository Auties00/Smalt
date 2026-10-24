package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawColorSpace;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Encodes linear output values with the transfer function of an output color space, through
 * lookup tables built on first use, one per function and depth.
 *
 * <p>An 8-bit table has {@code 2^16} entries indexed by the linear value scaled to 65535, finer than
 * any 8-bit step needs; a 16-bit table has {@code 2^20} entries, so even the steep start of the
 * sRGB curve advances by less than one output code per entry. This class is thread-safe.
 *
 * @implNote This implementation publishes each table with a compare-and-set, so two threads that
 * ask for the same table at once may both build it; only the tables of the color spaces in use are
 * ever built.
 */
final class Transfer {
    /**
     * The entries of an 8-bit table, minus one.
     */
    static final int SCALE_8 = (1 << 16) - 1;

    /**
     * The entries of a 16-bit table, minus one.
     */
    static final int SCALE_16 = (1 << 20) - 1;

    /**
     * The 8-bit tables, by color space ordinal, or {@code null} before first use.
     */
    private static final AtomicReferenceArray<byte[]> TABLES_8 =
            new AtomicReferenceArray<>(RawColorSpace.values().length);

    /**
     * The 16-bit tables, by color space ordinal, or {@code null} before first use.
     */
    private static final AtomicReferenceArray<short[]> TABLES_16 =
            new AtomicReferenceArray<>(RawColorSpace.values().length);

    /**
     * Prevents instantiation.
     */
    private Transfer() {
        throw new AssertionError();
    }

    /**
     * Encodes one linear value.
     *
     * @param space the color space whose transfer function applies
     * @param value the linear value, clamped to 0 to 1
     * @return the encoded value, from 0 to 1
     */
    static double encode(RawColorSpace space, double value) {
        var v = Math.clamp(value, 0, 1);
        return switch (space) {
            case SRGB, DISPLAY_P3 -> v <= 0.0031308 ? 12.92 * v : 1.055 * StrictMath.pow(v, 1 / 2.4) - 0.055;
            case ADOBE_RGB -> StrictMath.pow(v, 256.0 / 563.0);
            case PROPHOTO_RGB -> StrictMath.pow(v, 1 / 1.8);
            case LINEAR_SRGB, LINEAR_REC2020 -> v;
        };
    }

    /**
     * Returns the 8-bit table of a color space.
     *
     * @param space the color space whose transfer function applies
     * @return the table, indexed by the linear value times {@link #SCALE_8}; shared, not to be modified
     */
    static byte[] table8(RawColorSpace space) {
        var table = TABLES_8.get(space.ordinal());
        if (table != null) {
            return table;
        }
        table = new byte[SCALE_8 + 1];
        for (var index = 0; index <= SCALE_8; index++) {
            table[index] = (byte) Math.round(encode(space, index / (double) SCALE_8) * 255);
        }
        return TABLES_8.compareAndSet(space.ordinal(), null, table) ? table : TABLES_8.get(space.ordinal());
    }

    /**
     * Returns the 16-bit table of a color space.
     *
     * @param space the color space whose transfer function applies
     * @return the table, indexed by the linear value times {@link #SCALE_16}; shared, not to be
     * modified
     */
    static short[] table16(RawColorSpace space) {
        var table = TABLES_16.get(space.ordinal());
        if (table != null) {
            return table;
        }
        table = new short[SCALE_16 + 1];
        for (var index = 0; index <= SCALE_16; index++) {
            table[index] = (short) Math.round(encode(space, index / (double) SCALE_16) * 65535);
        }
        return TABLES_16.compareAndSet(space.ordinal(), null, table) ? table : TABLES_16.get(space.ordinal());
    }
}
