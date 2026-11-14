package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.raw.RawColorSpace;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Encodes linear output values with the transfer function of an output color space, through
 * lookup tables built on first use, one per function and depth.
 *
 * <p>An 8-bit table has {@code 2^16} entries indexed by the linear value scaled to 65535, finer than
 * any 8-bit step needs; a 16-bit table has {@code 2^20} entries, so even the steep start of the
 * sRGB curve advances by less than one output code per entry. Floating-point output interpolates a
 * table of {@code 2^16 + 1} samples over [0, 1] linearly, within about {@code 1e-7} of the curve, and
 * evaluates the curve's formula above 1. This class is thread-safe.
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
     * The intervals of a floating-point table.
     */
    static final int SCALE_FLOAT = 1 << 16;

    /**
     * The floating-point tables, by color space ordinal, or {@code null} before first use.
     */
    private static final AtomicReferenceArray<float[]> TABLES_FLOAT =
            new AtomicReferenceArray<>(RawColorSpace.values().length);

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
     * Encodes one linear value of any size: as {@link #encode} from 0 to 1, by the transfer function's power law
     * above 1.
     *
     * @param space the color space whose transfer function applies
     * @param value the linear value, non-negative
     * @return the encoded value
     */
    static double encodeExtended(RawColorSpace space, double value) {
        if (value <= 1) {
            return encode(space, value);
        }
        return switch (space) {
            case SRGB, DISPLAY_P3 -> 1.055 * StrictMath.pow(value, 1 / 2.4) - 0.055;
            case ADOBE_RGB -> StrictMath.pow(value, 256.0 / 563.0);
            case PROPHOTO_RGB -> StrictMath.pow(value, 1 / 1.8);
            case LINEAR_SRGB, LINEAR_REC2020 -> value;
        };
    }

    /**
     * Returns the floating-point table of a color space.
     *
     * @param space the color space whose transfer function applies
     * @return the {@link #SCALE_FLOAT} + 1 encoded values of the linear values {@code index / SCALE_FLOAT}; shared,
     * not to be modified
     */
    static float[] tableFloat(RawColorSpace space) {
        var table = TABLES_FLOAT.get(space.ordinal());
        if (table != null) {
            return table;
        }
        table = new float[SCALE_FLOAT + 1];
        for (var index = 0; index <= SCALE_FLOAT; index++) {
            table[index] = (float) encode(space, index / (double) SCALE_FLOAT);
        }
        return TABLES_FLOAT.compareAndSet(space.ordinal(), null, table) ? table : TABLES_FLOAT.get(space.ordinal());
    }

    /**
     * Encodes one linear value for floating-point output.
     *
     * @param space the color space whose transfer function applies
     * @param table the color space's table, from {@link #tableFloat}
     * @param value the linear value, non-negative
     * @return the encoded value
     */
    static float encodeFloat(RawColorSpace space, float[] table, float value) {
        if (value >= 1) {
            return value == 1 ? table[SCALE_FLOAT] : (float) encodeExtended(space, value);
        }
        var scaled = value * SCALE_FLOAT;
        var index = (int) scaled;
        return table[index] + (table[index + 1] - table[index]) * (scaled - index);
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
