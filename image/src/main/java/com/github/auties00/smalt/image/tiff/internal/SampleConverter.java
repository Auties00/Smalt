package com.github.auties00.smalt.image.tiff.internal;

/**
 * Converts rows of TIFF samples to RGBA pixels in two stages: unpacking stored samples to
 * normalized work samples, then mapping work samples to pixels through the photometric
 * interpretation.
 *
 * <p>Work samples are 8-bit when the stored samples are integers of at most 8 bits, and 16-bit
 * otherwise. Unpacking scales an {@code n}-bit sample {@code v} to {@code round(v * M / (2^n - 1))}
 * where {@code M} is 255 or 65535 (exact for 1, 2, 4, 8 and 16 bits), except palette indexes,
 * YCbCr codes and L*a*b* codes, which are kept as stored together with the extra samples of their
 * pixels; those extra samples are scaled the same way when they are mapped to pixels. Signed
 * integers are offset by {@code 2^(n-1)}, so their range maps onto the unsigned one.
 * Floating-point samples (16-, 32- and 64-bit IEEE, and the 24-bit format of
 * {@link #float24ToFloat(int)}) map the range from {@code SMinSampleValue} to
 * {@code SMaxSampleValue} (0 to 1 when the tags are absent) linearly onto 0..65535, clamping
 * values outside it and mapping NaN to 0.
 *
 * <p>Mapping follows libtiff's {@code TIFFReadRGBAImage}: white-is-zero gray is inverted;
 * a palette index selects a {@code ColorMap} entry; CMYK is converted naively, from the inks
 * divided by an associated alpha first, as {@code R = round((M - C) * (M - K) / M)} for work samples of
 * largest value {@code M} (likewise for G and B), which is the conversion of {@code ImageOps.toRgba}, and
 * any embedded profile is left to color management; YCbCr uses {@link YCbCrTables}, from codes kept as
 * stored, producing 8-bit values for 8-bit samples and 16-bit values otherwise; L*a*b* uses {@link LabTables},
 * from 8- or 16-bit codes kept as stored, always producing 8-bit values. The raw CMYK kind copies the
 * ink samples into the red, green, blue and alpha slots unchanged, for conversion through a CMYK
 * color profile. Associated (premultiplied) alpha is divided out, rounding to nearest, so the
 * pixels always carry straight alpha.
 */
public final class SampleConverter {
    /**
     * The gray kind (white-is-zero or black-is-zero).
     */
    public static final int GRAY = 0;

    /**
     * The RGB kind.
     */
    public static final int RGB = 1;

    /**
     * The palette kind.
     */
    public static final int PALETTE = 2;

    /**
     * The CMYK kind.
     */
    public static final int CMYK = 3;

    /**
     * The YCbCr kind, without subsampling.
     */
    public static final int YCBCR = 4;

    /**
     * The L*a*b* kind (CIE, ICC or ITU encoding).
     */
    public static final int LAB = 5;

    /**
     * The raw CMYK kind: cyan, magenta, yellow and black copied into the four pixel slots.
     */
    public static final int RAW_CMYK = 6;

    /**
     * The photometric kind.
     */
    private final int kind;

    /**
     * Whether gray is white-is-zero.
     */
    private final boolean invert;

    /**
     * The number of samples per pixel in work rows.
     */
    private final int samples;

    /**
     * The index of the alpha sample in a pixel, or {@code -1}.
     */
    private final int alpha;

    /**
     * Whether the alpha is associated (premultiplied).
     */
    private final boolean associated;

    /**
     * The stored bits per sample.
     */
    private final int bits;

    /**
     * Whether the stored samples are signed integers.
     */
    private final boolean signed;

    /**
     * Whether the stored samples are floating point.
     */
    private final boolean floating;

    /**
     * Whether stored multi-byte samples are little-endian.
     */
    private final boolean little;

    /**
     * The real mapped to 0, for floating-point samples.
     */
    private final double floatMin;

    /**
     * The scale from real to 16-bit work samples.
     */
    private final double floatScale;

    /**
     * The palette as packed 8-bit {@code r << 16 | g << 8 | b}, or {@code null}.
     */
    private final int[] palette8;

    /**
     * The palette as 16-bit {@code r, g, b} triples, or {@code null}.
     */
    private final short[] palette16;

    /**
     * The YCbCr tables, or {@code null}.
     */
    private final YCbCrTables ycbcr;

    /**
     * The L*a*b* tables, or {@code null}.
     */
    private final LabTables lab;

    /**
     * The largest stored value of an alpha sample that unpacking keeps as stored, or 0 when
     * unpacking scales alpha samples.
     */
    private final int storedAlphaMax;

    /**
     * Configures a converter.
     *
     * @param kind       the photometric kind
     * @param invert     whether gray is white-is-zero
     * @param samples    the samples per pixel
     * @param alpha      the alpha sample index, or {@code -1}
     * @param associated whether the alpha is premultiplied
     * @param bits       the stored bits per sample
     * @param format     the sample format: 1 unsigned, 2 signed, 3 floating point
     * @param little     whether stored multi-byte samples are little-endian
     * @param floatMin   the real mapped to 0
     * @param floatMax   the real mapped to the maximum
     * @param colorMap   the {@code ColorMap} values (all reds, all greens, all blues), or
     *                   {@code null}
     * @param ycbcr      the YCbCr tables, or {@code null}
     * @param lab        the L*a*b* tables, or {@code null}
     */
    public SampleConverter(int kind, boolean invert, int samples, int alpha, boolean associated, int bits, int format,
                           boolean little, double floatMin, double floatMax, long[] colorMap, YCbCrTables ycbcr,
                           LabTables lab) {
        this.kind = kind;
        this.invert = invert;
        this.samples = samples;
        this.alpha = alpha;
        this.associated = associated;
        this.bits = bits;
        this.signed = format == 2;
        this.floating = format == 3;
        this.little = little;
        this.floatMin = floatMin;
        this.floatScale = floatMax > floatMin ? 65535.0 / (floatMax - floatMin) : 65535.0;
        this.ycbcr = ycbcr;
        this.lab = lab;
        var unscaled = kind == PALETTE || kind == YCBCR || kind == LAB;
        this.storedAlphaMax = unscaled && !floating && bits != 8 && bits < 16 ? (1 << bits) - 1 : 0;
        if (colorMap != null) {
            var entries = colorMap.length / 3;
            var eightBit = true;
            for (var value : colorMap) {
                if (value > 255) {
                    eightBit = false;
                    break;
                }
            }
            palette8 = new int[entries];
            palette16 = new short[entries * 3];
            for (var i = 0; i < entries; i++) {
                var r = (int) colorMap[i] & 0xFFFF;
                var g = (int) colorMap[entries + i] & 0xFFFF;
                var b = (int) colorMap[2 * entries + i] & 0xFFFF;
                if (eightBit) {
                    palette8[i] = r << 16 | g << 8 | b;
                    palette16[i * 3] = (short) (r * 257);
                    palette16[i * 3 + 1] = (short) (g * 257);
                    palette16[i * 3 + 2] = (short) (b * 257);
                } else {
                    palette8[i] = reduce(r) << 16 | reduce(g) << 8 | reduce(b);
                    palette16[i * 3] = (short) r;
                    palette16[i * 3 + 1] = (short) g;
                    palette16[i * 3 + 2] = (short) b;
                }
            }
        } else {
            palette8 = null;
            palette16 = null;
        }
    }

    /**
     * Returns whether work samples are 16-bit.
     *
     * @return {@code true} for stored samples wider than 8 bits and for floating point
     */
    public boolean wide() {
        return floating || bits > 8;
    }

    /**
     * Returns whether the pixels can be produced at 16 bits.
     *
     * @return {@code true} when the work samples are 16-bit and the kind keeps their precision;
     *         {@code false} for L*a*b*, whose rendering is 8-bit
     */
    public boolean wideOutput() {
        return wide() && kind != LAB;
    }

    /**
     * Returns whether stored samples can serve directly as 8-bit work samples.
     *
     * @return {@code true} for unsigned 8-bit integers
     */
    public boolean direct() {
        return bits == 8 && !signed && !floating;
    }

    /**
     * Returns whether the pixels carry alpha.
     *
     * @return {@code true} when an alpha sample exists
     */
    public boolean hasAlpha() {
        return alpha >= 0;
    }

    /**
     * Unpacks stored samples into 8-bit work samples.
     *
     * @param raw        the stored row
     * @param rawOffset  the index of the row's first byte
     * @param count      the number of samples
     * @param work       the destination
     * @param workOffset the index of the first destination sample
     * @param workStride the distance between destination samples
     */
    public void unpack8(byte[] raw, int rawOffset, int count, byte[] work, int workOffset, int workStride) {
        if (bits == 8) {
            var flip = signed ? 0x80 : 0;
            for (var i = 0; i < count; i++) {
                work[workOffset + i * workStride] = (byte) (raw[rawOffset + i] ^ flip);
            }
            return;
        }
        var max = (1 << bits) - 1;
        var scale = kind != PALETTE && kind != YCBCR && kind != LAB;
        var flip = signed ? 1 << (bits - 1) : 0;
        var accumulator = 0L;
        var available = 0;
        var position = rawOffset;
        for (var i = 0; i < count; i++) {
            while (available < bits) {
                accumulator = accumulator << 8 | (raw[position++] & 0xFF);
                available += 8;
            }
            available -= bits;
            var value = ((int) (accumulator >>> available) & max) ^ flip;
            work[workOffset + i * workStride] = (byte) (scale ? (value * 255 + (max >> 1)) / max : value);
        }
    }

    /**
     * Unpacks stored samples into 16-bit work samples.
     *
     * @param raw        the stored row
     * @param rawOffset  the index of the row's first byte
     * @param count      the number of samples
     * @param work       the destination
     * @param workOffset the index of the first destination sample
     * @param workStride the distance between destination samples
     * @param bigEndian  whether floating-point samples are big-endian whatever the file's byte
     *                   order (after the floating-point predictor)
     */
    public void unpack16(byte[] raw, int rawOffset, int count, short[] work, int workOffset, int workStride,
                         boolean bigEndian) {
        if (floating) {
            var lsb = little && !bigEndian;
            for (var i = 0; i < count; i++) {
                double value = switch (bits) {
                    case 16 -> Float.float16ToFloat((short) read(raw, rawOffset + i * 2, 2, lsb));
                    case 24 -> float24ToFloat((int) read(raw, rawOffset + i * 3, 3, lsb));
                    case 32 -> Float.intBitsToFloat((int) read(raw, rawOffset + i * 4, 4, lsb));
                    default -> Double.longBitsToDouble(read(raw, rawOffset + i * 8, 8, lsb));
                };
                var scaled = (value - floatMin) * floatScale;
                work[workOffset + i * workStride] = (short) (scaled >= 0 ? (scaled >= 65535 ? 65535 : (int) (scaled + 0.5)) : 0);
            }
            return;
        }
        var scale = kind != PALETTE && kind != YCBCR && kind != LAB;
        switch (bits) {
            case 16 -> {
                var flip = signed ? 0x8000 : 0;
                for (var i = 0; i < count; i++) {
                    work[workOffset + i * workStride] = (short) ((int) read(raw, rawOffset + i * 2, 2, little) ^ flip);
                }
            }
            case 24, 32 -> {
                var bytes = bits / 8;
                var max = (1L << bits) - 1;
                var flip = signed ? 1L << (bits - 1) : 0;
                for (var i = 0; i < count; i++) {
                    var value = read(raw, rawOffset + i * bytes, bytes, little) ^ flip;
                    work[workOffset + i * workStride] = (short) ((value * 65535 + (max >> 1)) / max);
                }
            }
            default -> {
                var max = (1L << bits) - 1;
                var flip = signed ? 1L << (bits - 1) : 0;
                var accumulator = 0L;
                var available = 0;
                var position = rawOffset;
                for (var i = 0; i < count; i++) {
                    while (available < bits) {
                        accumulator = accumulator << 8 | (raw[position++] & 0xFF);
                        available += 8;
                    }
                    available -= bits;
                    var value = ((accumulator >>> available) & max) ^ flip;
                    work[workOffset + i * workStride] = (short) (scale ? (value * 65535 + (max >> 1)) / max : value);
                }
            }
        }
    }

    /**
     * Unpacks stored floating-point samples into floats as stored, neither normalized nor clamped;
     * 64-bit samples are narrowed to the nearest float.
     *
     * @param raw        the stored row
     * @param rawOffset  the index of the row's first byte
     * @param count      the number of samples
     * @param work       the destination
     * @param workOffset the index of the first destination sample
     * @param workStride the distance between destination samples
     * @param bigEndian  whether the samples are big-endian whatever the file's byte order (after
     *                   the floating-point predictor)
     */
    public void unpackFloat(byte[] raw, int rawOffset, int count, float[] work, int workOffset, int workStride,
                            boolean bigEndian) {
        var lsb = little && !bigEndian;
        for (var i = 0; i < count; i++) {
            work[workOffset + i * workStride] = switch (bits) {
                case 16 -> Float.float16ToFloat((short) read(raw, rawOffset + i * 2, 2, lsb));
                case 24 -> float24ToFloat((int) read(raw, rawOffset + i * 3, 3, lsb));
                case 32 -> Float.intBitsToFloat((int) read(raw, rawOffset + i * 4, 4, lsb));
                default -> (float) Double.longBitsToDouble(read(raw, rawOffset + i * 8, 8, lsb));
            };
        }
    }

    /**
     * Converts a 24-bit floating-point value to a float.
     *
     * <p>The format has 1 sign bit, 7 exponent bits biased by 63 and 16 fraction bits, with the
     * IEEE 754 rules: exponent 0 holds zero and subnormal values, exponent 127 infinity and NaN.
     * Every value is exactly representable as a float.
     *
     * @param bits the 24 bits, sign first, in the low bits of the integer
     * @return the value
     */
    public static float float24ToFloat(int bits) {
        var sign = (bits >>> 23 & 1) << 31;
        var exponent = bits >>> 16 & 0x7F;
        var fraction = bits & 0xFFFF;
        if (exponent == 0x7F) {
            return Float.intBitsToFloat(sign | 0x7F800000 | fraction << 7);
        }
        if (exponent == 0) {
            // subnormal: fraction * 2^(1 - 63 - 16)
            var magnitude = Math.scalb((float) fraction, -78);
            return sign != 0 ? -magnitude : magnitude;
        }
        return Float.intBitsToFloat(sign | (exponent + 64) << 23 | fraction << 7);
    }

    /**
     * Returns whether the stored samples are floating point.
     *
     * @return {@code true} for {@code SampleFormat} 3
     */
    public boolean floating() {
        return floating;
    }

    /**
     * Maps 8-bit work samples to 8-bit RGBA pixels.
     *
     * @param work       the work samples, {@code samples} per pixel
     * @param workOffset the index of the first pixel's first sample
     * @param pixels     the number of pixels
     * @param out        the RGBA destination
     * @param outOffset  the index of the first pixel's red byte
     */
    public void toRgba8(byte[] work, int workOffset, int pixels, byte[] out, int outOffset) {
        var spp = samples;
        var a = alpha;
        switch (kind) {
            case GRAY -> {
                var flip = invert ? 0xFF : 0;
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    var g = (byte) (work[in] ^ flip);
                    out[o] = g;
                    out[o + 1] = g;
                    out[o + 2] = g;
                    out[o + 3] = a >= 0 ? work[in + a] : (byte) 0xFF;
                }
            }
            case RGB -> {
                if (spp == 3 && a < 0) {
                    for (var i = 0; i < pixels; i++) {
                        var in = workOffset + i * 3;
                        var o = outOffset + i * 4;
                        out[o] = work[in];
                        out[o + 1] = work[in + 1];
                        out[o + 2] = work[in + 2];
                        out[o + 3] = (byte) 0xFF;
                    }
                } else if (spp == 4 && a == 3 && !associated) {
                    System.arraycopy(work, workOffset, out, outOffset, pixels * 4);
                } else {
                    for (var i = 0; i < pixels; i++) {
                        var in = workOffset + i * spp;
                        var o = outOffset + i * 4;
                        out[o] = work[in];
                        out[o + 1] = work[in + 1];
                        out[o + 2] = work[in + 2];
                        out[o + 3] = a >= 0 ? work[in + a] : (byte) 0xFF;
                    }
                }
            }
            case PALETTE -> {
                var map = palette8;
                var last = map.length - 1;
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    var rgb = map[Math.min(work[in] & 0xFF, last)];
                    out[o] = (byte) (rgb >>> 16);
                    out[o + 1] = (byte) (rgb >>> 8);
                    out[o + 2] = (byte) rgb;
                    out[o + 3] = a >= 0 ? alpha8(work[in + a]) : (byte) 0xFF;
                }
            }
            case LAB -> {
                var tables = lab;
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    var rgb = tables.rgb8(work[in] & 0xFF, work[in + 1] & 0xFF, work[in + 2] & 0xFF);
                    out[o] = (byte) (rgb >>> 16);
                    out[o + 1] = (byte) (rgb >>> 8);
                    out[o + 2] = (byte) rgb;
                    out[o + 3] = a >= 0 ? alpha8(work[in + a]) : (byte) 0xFF;
                }
            }
            case RAW_CMYK -> {
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    out[o] = work[in];
                    out[o + 1] = work[in + 1];
                    out[o + 2] = work[in + 2];
                    out[o + 3] = work[in + 3];
                }
            }
            case CMYK -> {
                var divide = associated && a >= 0;
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    var alphaValue = a >= 0 ? work[in + a] & 0xFF : 255;
                    var c = work[in] & 0xFF;
                    var m = work[in + 1] & 0xFF;
                    var y = work[in + 2] & 0xFF;
                    var k = work[in + 3] & 0xFF;
                    if (divide) {
                        c = unpremultiply(c, alphaValue, 255);
                        m = unpremultiply(m, alphaValue, 255);
                        y = unpremultiply(y, alphaValue, 255);
                        k = unpremultiply(k, alphaValue, 255);
                    }
                    out[o] = (byte) cmyk(c, k, 255);
                    out[o + 1] = (byte) cmyk(m, k, 255);
                    out[o + 2] = (byte) cmyk(y, k, 255);
                    out[o + 3] = (byte) alphaValue;
                }
                return;
            }
            default -> {
                var tables = ycbcr;
                var eightBit = tables.eightBit();
                for (var i = 0; i < pixels; i++) {
                    var in = workOffset + i * spp;
                    var o = outOffset + i * 4;
                    if (eightBit) {
                        var rgb = tables.toRgb(work[in] & 0xFF, work[in + 1] & 0xFF, work[in + 2] & 0xFF);
                        out[o] = (byte) (rgb >>> 16);
                        out[o + 1] = (byte) (rgb >>> 8);
                        out[o + 2] = (byte) rgb;
                    } else {
                        var rgb = tables.toRgb16(work[in] & 0xFF, work[in + 1] & 0xFF, work[in + 2] & 0xFF);
                        out[o] = (byte) reduce((int) (rgb >>> 32) & 0xFFFF);
                        out[o + 1] = (byte) reduce((int) (rgb >>> 16) & 0xFFFF);
                        out[o + 2] = (byte) reduce((int) rgb & 0xFFFF);
                    }
                    out[o + 3] = a >= 0 ? alpha8(work[in + a]) : (byte) 0xFF;
                }
            }
        }
        if (associated && a >= 0) {
            unpremultiply8(out, outOffset, pixels);
        }
    }

    /**
     * Maps 16-bit work samples to RGBA pixels, 16-bit or reduced to 8 bits.
     *
     * @param work       the work samples, {@code samples} per pixel
     * @param workOffset the index of the first pixel's first sample
     * @param pixels     the number of pixels
     * @param out8       the 8-bit RGBA destination, or {@code null} to write {@code out16}
     * @param out16      the 16-bit RGBA destination, used when {@code out8} is {@code null}
     * @param outOffset  the index of the first pixel's red sample
     */
    public void toRgba16(short[] work, int workOffset, int pixels, byte[] out8, short[] out16, int outOffset) {
        var spp = samples;
        var a = alpha;
        if (kind == CMYK) {
            toRgba16Cmyk(work, workOffset, pixels, out8, out16, outOffset);
            return;
        }
        for (var i = 0; i < pixels; i++) {
            var in = workOffset + i * spp;
            int r;
            int g;
            int b;
            switch (kind) {
                case GRAY -> {
                    var v = work[in] & 0xFFFF;
                    if (invert) {
                        v = 65535 - v;
                    }
                    r = v;
                    g = v;
                    b = v;
                }
                case RGB -> {
                    r = work[in] & 0xFFFF;
                    g = work[in + 1] & 0xFFFF;
                    b = work[in + 2] & 0xFFFF;
                }
                case PALETTE -> {
                    var index = Math.min(work[in] & 0xFFFF, palette16.length / 3 - 1) * 3;
                    r = palette16[index] & 0xFFFF;
                    g = palette16[index + 1] & 0xFFFF;
                    b = palette16[index + 2] & 0xFFFF;
                }
                case LAB -> {
                    var rgb = lab.rgb16(work[in] & 0xFFFF, work[in + 1] & 0xFFFF, work[in + 2] & 0xFFFF);
                    r = (rgb >>> 16 & 0xFF) * 257;
                    g = (rgb >>> 8 & 0xFF) * 257;
                    b = (rgb & 0xFF) * 257;
                }
                case YCBCR -> {
                    var rgb = ycbcr.toRgb16(work[in] & 0xFFFF, work[in + 1] & 0xFFFF, work[in + 2] & 0xFFFF);
                    r = (int) (rgb >>> 32) & 0xFFFF;
                    g = (int) (rgb >>> 16) & 0xFFFF;
                    b = (int) rgb & 0xFFFF;
                }
                default -> {
                    r = work[in] & 0xFFFF;
                    g = work[in + 1] & 0xFFFF;
                    b = work[in + 2] & 0xFFFF;
                }
            }
            var alphaValue = kind == RAW_CMYK ? work[in + 3] & 0xFFFF : a >= 0 ? alpha16(work[in + a]) : 65535;
            if (associated && a >= 0) {
                r = unpremultiply(r, alphaValue, 65535);
                g = unpremultiply(g, alphaValue, 65535);
                b = unpremultiply(b, alphaValue, 65535);
            }
            var o = outOffset + i * 4;
            if (out8 != null) {
                out8[o] = (byte) reduce(r);
                out8[o + 1] = (byte) reduce(g);
                out8[o + 2] = (byte) reduce(b);
                out8[o + 3] = (byte) reduce(alphaValue);
            } else {
                out16[o] = (short) r;
                out16[o + 1] = (short) g;
                out16[o + 2] = (short) b;
                out16[o + 3] = (short) alphaValue;
            }
        }
    }

    /**
     * Maps 16-bit CMYK work samples to RGBA pixels, 16-bit or 8-bit.
     *
     * @param work       the work samples, {@code samples} per pixel
     * @param workOffset the index of the first pixel's first sample
     * @param pixels     the number of pixels
     * @param out8       the 8-bit RGBA destination, or {@code null} to write {@code out16}
     * @param out16      the 16-bit RGBA destination, used when {@code out8} is {@code null}
     * @param outOffset  the index of the first pixel's red sample
     */
    private void toRgba16Cmyk(short[] work, int workOffset, int pixels, byte[] out8, short[] out16, int outOffset) {
        var spp = samples;
        var a = alpha;
        var divide = associated && a >= 0;
        for (var i = 0; i < pixels; i++) {
            var in = workOffset + i * spp;
            var alphaValue = a >= 0 ? work[in + a] & 0xFFFF : 65535;
            var c = work[in] & 0xFFFF;
            var m = work[in + 1] & 0xFFFF;
            var y = work[in + 2] & 0xFFFF;
            var k = work[in + 3] & 0xFFFF;
            if (divide) {
                c = unpremultiply(c, alphaValue, 65535);
                m = unpremultiply(m, alphaValue, 65535);
                y = unpremultiply(y, alphaValue, 65535);
                k = unpremultiply(k, alphaValue, 65535);
            }
            var o = outOffset + i * 4;
            var r = cmyk(c, k, 65535);
            var g = cmyk(m, k, 65535);
            var b = cmyk(y, k, 65535);
            if (out8 != null) {
                out8[o] = (byte) reduce(r);
                out8[o + 1] = (byte) reduce(g);
                out8[o + 2] = (byte) reduce(b);
                out8[o + 3] = (byte) reduce(alphaValue);
            } else {
                out16[o] = (short) r;
                out16[o + 1] = (short) g;
                out16[o + 2] = (short) b;
                out16[o + 3] = (short) alphaValue;
            }
        }
    }

    /**
     * Converts one CMYK ink pair to an RGB component without color management.
     *
     * @param ink the cyan, magenta or yellow amount
     * @param black the black amount
     * @param max the largest work sample, 255 or 65535
     * @return {@code round((max - ink) * (max - black) / max)}; {@code max} is odd, so no value is
     *         halfway
     */
    private static int cmyk(int ink, int black, int max) {
        return (int) (((long) (max - ink) * (max - black) + max / 2) / max);
    }

    /**
     * Divides premultiplied 8-bit pixels by their alpha.
     *
     * @param out       the RGBA pixels
     * @param outOffset the index of the first pixel
     * @param pixels    the number of pixels
     */
    private static void unpremultiply8(byte[] out, int outOffset, int pixels) {
        for (var i = 0; i < pixels; i++) {
            var o = outOffset + i * 4;
            var alphaValue = out[o + 3] & 0xFF;
            if (alphaValue == 255) {
                continue;
            }
            out[o] = (byte) unpremultiply(out[o] & 0xFF, alphaValue, 255);
            out[o + 1] = (byte) unpremultiply(out[o + 1] & 0xFF, alphaValue, 255);
            out[o + 2] = (byte) unpremultiply(out[o + 2] & 0xFF, alphaValue, 255);
        }
    }

    /**
     * Divides one premultiplied component by its alpha.
     *
     * @param value      the component
     * @param alphaValue the alpha
     * @param max        the largest sample value
     * @return the straight component, {@code 0} when the alpha is zero
     */
    private static int unpremultiply(int value, int alphaValue, int max) {
        if (alphaValue == 0) {
            return 0;
        }
        return (int) Math.min(max, ((long) value * max + (alphaValue >> 1)) / alphaValue);
    }

    /**
     * Scales an 8-bit work alpha sample that unpacking kept as stored.
     *
     * @param value the work sample
     * @return the alpha scaled to 0..255, {@code round(v * 255 / (2^n - 1))} for {@code n}-bit
     *         samples kept as stored, and {@code value} itself otherwise
     */
    private byte alpha8(byte value) {
        var max = storedAlphaMax;
        return max == 0 ? value : (byte) (((value & 0xFF) * 255 + (max >> 1)) / max);
    }

    /**
     * Scales a 16-bit work alpha sample that unpacking kept as stored.
     *
     * @param value the work sample
     * @return the alpha scaled to 0..65535, {@code round(v * 65535 / (2^n - 1))} for {@code n}-bit
     *         samples kept as stored, and {@code value} itself otherwise
     */
    private int alpha16(short value) {
        var max = storedAlphaMax;
        return max == 0 ? value & 0xFFFF : ((value & 0xFFFF) * 65535 + (max >> 1)) / max;
    }

    /**
     * Reduces a 16-bit sample to 8 bits with exact rounding.
     *
     * @param value the sample, from 0 to 65535
     * @return {@code (value * 255 + 32767) / 65535}
     */
    private static int reduce(int value) {
        return (value * 255 + 32767) / 65535;
    }

    /**
     * Reads an unsigned integer of up to 8 bytes.
     *
     * @param data   the bytes
     * @param at     the index of the first byte
     * @param bytes  the number of bytes
     * @param little whether the value is little-endian
     * @return the value
     */
    private static long read(byte[] data, int at, int bytes, boolean little) {
        var value = 0L;
        if (little) {
            for (var i = bytes - 1; i >= 0; i--) {
                value = value << 8 | (data[at + i] & 0xFF);
            }
        } else {
            for (var i = 0; i < bytes; i++) {
                value = value << 8 | (data[at + i] & 0xFF);
            }
        }
        return value;
    }
}
