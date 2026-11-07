package com.github.auties00.smalt.image.tiff.internal;

/**
 * Rewrites rows of TIFF samples that {@link SampleConverter} cannot read directly into a uniform
 * layout it can: samples whose {@code BitsPerSample} differ between the samples of a pixel,
 * complex samples ({@code SampleFormat} 5 and 6), and L*a*b* samples other than 8- and 16-bit
 * integers.
 *
 * <p>The output holds one sample per stored sample, in the file's byte order, of one of three
 * kinds:
 * <ul>
 *   <li>8-bit unsigned integers, when every stored sample is an integer of at most 8 bits: an
 *       {@code n}-bit sample {@code v} becomes {@code round(v * 255 / (2^n - 1))}, as
 *       {@link SampleConverter} scales uniform samples;</li>
 *   <li>16-bit unsigned integers, for other integer samples ({@code round(v * 65535 / (2^n - 1))}),
 *       for complex integers and for L*a*b*;</li>
 *   <li>32-bit floats, for floating-point and complex floating-point samples, 64-bit values
 *       narrowed to the nearest float.</li>
 * </ul>
 * Signed integers are offset by {@code 2^(n-1)} first. A complex sample of {@code 2h} bits holds
 * its real part then its imaginary part, each of {@code h} bits; it becomes its magnitude
 * {@code sqrt(re^2 + im^2)}: for complex integers, rounded and clamped to {@code 2^(h-1) - 1}
 * and scaled from that range to 16 bits; for complex floating point, as a float.
 *
 * <p>L*a*b* samples become the 16-bit codes of the page's own encoding (as {@link LabTables}
 * reads 16-bit samples): integer samples of {@code n} bits keep their meaning, {@code L*} scaled
 * from {@code 0..2^n - 1} (CIE and ITU) or to the ICC version 2 range ending at 0xFF00, and
 * {@code a*} and {@code b*} scaled by {@code 2^(16-n)} (CIE, signed, and ICC, offset) or from
 * {@code 0..2^n - 1} (ITU, whose {@code Decode} ranges stay linear); floating-point samples hold
 * {@code L*}, {@code a*} and {@code b*} themselves and are rounded to the nearest code, clamped
 * to the code range. Extra samples of an L*a*b* page become 16-bit integers, floating-point ones
 * mapped from {@code SMinSampleValue..SMaxSampleValue} as {@link SampleConverter} maps them.
 *
 * <p>A sample whose width is a multiple of 8 and that starts on a byte boundary is read in the
 * file's byte order (big-endian for floating-point samples after the floating-point predictor);
 * other samples are packed high-order bit first.
 *
 * <p>Instances are immutable and thread-safe. Arguments are not checked; values outside the
 * documented ranges give unspecified results.
 */
public final class SampleNormalizer {
    /**
     * The output kind: 8-bit unsigned integers.
     */
    public static final int UINT8 = 8;

    /**
     * The output kind: 16-bit unsigned integers.
     */
    public static final int UINT16 = 16;

    /**
     * The output kind: 32-bit floats.
     */
    public static final int FLOAT32 = 32;

    /**
     * The stored bits per sample, one per sample of a pixel.
     */
    private final int[] bits;

    /**
     * The stored sample format: 1 unsigned, 2 signed, 3 floating point, 5 complex integer, 6
     * complex floating point.
     */
    private final int format;

    /**
     * The L*a*b* photometric interpretation (8, 9 or 10), or 0 for other pages.
     */
    private final int lab;

    /**
     * The ITU L*a*b* {@code Decode} ranges, six values, or {@code null}.
     */
    private final double[] decode;

    /**
     * Whether stored multi-byte samples are little-endian.
     */
    private final boolean little;

    /**
     * The real mapped to 0, for floating-point extra samples of an L*a*b* page.
     */
    private final double floatMin;

    /**
     * The scale from real to 16-bit codes, for floating-point extra samples of an L*a*b* page.
     */
    private final double floatScale;

    /**
     * The output kind: {@link #UINT8}, {@link #UINT16} or {@link #FLOAT32}.
     */
    private final int output;

    /**
     * Configures a normalizer.
     *
     * @param bits     the stored bits per sample, one per sample of a pixel
     * @param format   the stored sample format, from 1 to 6 except 4
     * @param lab      the L*a*b* photometric interpretation (8, 9 or 10), or 0
     * @param decode   the ITU L*a*b* {@code Decode} values, or {@code null} for the defaults
     * @param little   whether stored multi-byte samples are little-endian
     * @param floatMin the {@code SMinSampleValue} of floating-point samples
     * @param floatMax the {@code SMaxSampleValue} of floating-point samples
     */
    public SampleNormalizer(int[] bits, int format, int lab, double[] decode, boolean little, double floatMin,
                            double floatMax) {
        this.bits = bits.clone();
        this.format = format;
        this.lab = lab;
        this.decode = decode != null && decode.length >= 6 ? decode.clone() : new double[]{0, 100, -85, 85, -75, 125};
        this.little = little;
        this.floatMin = floatMin;
        this.floatScale = floatMax > floatMin ? 65535.0 / (floatMax - floatMin) : 65535.0;
        var widest = 0;
        for (var value : bits) {
            widest = Math.max(widest, value);
        }
        if (lab != 0 || format == 5) {
            output = UINT16;
        } else if (format == 3 || format == 6) {
            output = FLOAT32;
        } else {
            output = widest <= 8 ? UINT8 : UINT16;
        }
    }

    /**
     * Returns whether a page needs normalizing.
     *
     * @param bits   the stored bits per sample, one per sample of a pixel
     * @param format the stored sample format
     * @param lab    whether the samples are L*a*b*
     * @return {@code true} when the widths differ, the samples are complex, or L*a*b* samples are
     * not 8- or 16-bit integers
     */
    public static boolean needed(int[] bits, int format, boolean lab) {
        for (var value : bits) {
            if (value != bits[0]) {
                return true;
            }
        }
        return format == 5 || format == 6 || (lab && (format == 3 || (bits[0] != 8 && bits[0] != 16)));
    }

    /**
     * {@return the output kind: {@link #UINT8}, {@link #UINT16} or {@link #FLOAT32}}
     */
    public int output() {
        return output;
    }

    /**
     * {@return the size of one output sample in bytes}
     */
    public int outputBytes() {
        return output / 8;
    }

    /**
     * {@return the sample format of the output: 3 for floats, 1 otherwise}
     */
    public int outputFormat() {
        return output == FLOAT32 ? 3 : 1;
    }

    /**
     * Normalizes one row.
     *
     * @param src       the stored row
     * @param srcOffset the index of the row's first byte
     * @param count     the number of samples
     * @param plane     the sample of every value of a separate plane, or -1 for interleaved samples
     *                  starting with the first sample of a pixel
     * @param bigEndian whether whole-byte floating-point samples are big-endian whatever the file's
     *                  byte order (after the floating-point predictor)
     * @param dst       the destination, {@code count * outputBytes()} bytes from {@code dstOffset}
     * @param dstOffset the index of the first destination byte
     */
    public void normalize(byte[] src, int srcOffset, int count, int plane, boolean bigEndian, byte[] dst,
                          int dstOffset) {
        var samples = bits.length;
        var bit = (long) srcOffset * 8;
        var at = dstOffset;
        for (var i = 0; i < count; i++) {
            var sample = plane >= 0 ? plane : i % samples;
            var width = bits[sample];
            switch (output) {
                case UINT8 -> dst[at] = (byte) scaled(src, bit, width, 255);
                case UINT16 -> put16(dst, at, lab != 0 ? labCode(src, bit, width, sample, bigEndian)
                        : format == 5 ? complexInteger(src, bit, width) : scaled(src, bit, width, 65535));
                default -> putFloat(dst, at, format == 6 ? complexFloat(src, bit, width, bigEndian)
                        : (float) real(src, bit, width, bigEndian));
            }
            bit += width;
            at += output / 8;
        }
    }

    /**
     * Reads an integer sample and scales it to the output range.
     *
     * @param src   the stored bytes
     * @param bit   the index of the sample's first bit
     * @param width the sample width in bits, from 1 to 32
     * @param max   the largest output value, 255 or 65535
     * @return the scaled value
     */
    private int scaled(byte[] src, long bit, int width, int max) {
        var value = unsigned(src, bit, width, false);
        if (format == 2) {
            value ^= 1L << (width - 1);
        }
        if (width == 8 && max == 255 || width == 16 && max == 65535) {
            return (int) value;
        }
        var range = (1L << width) - 1;
        return (int) ((value * max + (range >> 1)) / range);
    }

    /**
     * Reads the magnitude of a complex integer sample, scaled to 16 bits.
     *
     * @param src   the stored bytes
     * @param bit   the index of the sample's first bit
     * @param width the width of the whole complex sample in bits
     * @return the scaled magnitude
     */
    private int complexInteger(byte[] src, long bit, int width) {
        var half = width / 2;
        var re = signed(unsigned(src, bit, half, false), half);
        var im = signed(unsigned(src, bit + half, half, false), half);
        var limit = (double) ((1L << (half - 1)) - 1);
        var magnitude = Math.min(StrictMath.hypot(re, im), limit);
        return (int) Math.round(Math.rint(magnitude) * 65535 / limit);
    }

    /**
     * Reads the magnitude of a complex floating-point sample.
     *
     * @param src       the stored bytes
     * @param bit       the index of the sample's first bit
     * @param width     the width of the whole complex sample in bits
     * @param bigEndian whether the parts are big-endian whatever the file's byte order
     * @return the magnitude, narrowed to a float
     */
    private float complexFloat(byte[] src, long bit, int width, boolean bigEndian) {
        var half = width / 2;
        return (float) StrictMath.hypot(real(src, bit, half, bigEndian), real(src, bit + half, half, bigEndian));
    }

    /**
     * Reads a floating-point value of 16, 24, 32 or 64 bits.
     *
     * @param src       the stored bytes
     * @param bit       the index of the value's first bit
     * @param width     the width in bits
     * @param bigEndian whether the value is big-endian whatever the file's byte order
     * @return the value
     */
    private double real(byte[] src, long bit, int width, boolean bigEndian) {
        var raw = unsigned(src, bit, width, bigEndian);
        return switch (width) {
            case 16 -> Float.float16ToFloat((short) raw);
            case 24 -> SampleConverter.float24ToFloat((int) raw);
            case 32 -> Float.intBitsToFloat((int) raw);
            default -> Double.longBitsToDouble(raw);
        };
    }

    /**
     * Reads an L*a*b* sample, or an extra sample of an L*a*b* page, as a 16-bit code.
     *
     * @param src       the stored bytes
     * @param bit       the index of the sample's first bit
     * @param width     the width in bits
     * @param sample    the sample index in the pixel: 0 for L*, 1 for a*, 2 for b*, more for extra
     *                  samples
     * @param bigEndian whether whole-byte floating-point samples are big-endian
     * @return the code, from 0 to 65535
     */
    private int labCode(byte[] src, long bit, int width, int sample, boolean bigEndian) {
        if (format == 3) {
            var value = real(src, bit, width, bigEndian);
            if (sample >= 3) {
                var scaledValue = (value - floatMin) * floatScale;
                return scaledValue >= 0 ? (scaledValue >= 65535 ? 65535 : (int) (scaledValue + 0.5)) : 0;
            }
            return switch (lab) {
                case LabTables.CIELAB -> sample == 0 ? code(value * 65535 / 100, 0, 65535)
                        : code(value * 256, -32768, 32767) & 0xFFFF;
                case LabTables.ICCLAB -> sample == 0 ? code(value * 65280 / 100, 0, 65535)
                        : code((value + 128) * 256, 0, 65535);
                default -> {
                    var low = decode[sample * 2];
                    var high = decode[sample * 2 + 1];
                    yield code(high != low ? (value - low) / (high - low) * 65535 : 0, 0, 65535);
                }
            };
        }
        var value = unsigned(src, bit, width, false);
        var range = (double) ((1L << width) - 1);
        if (sample >= 3 || lab == LabTables.ITULAB) {
            return width == 16 ? (int) value : code(value * 65535 / range, 0, 65535);
        }
        if (sample == 0) {
            if (width == 16) {
                return (int) value;
            }
            return code(value * (lab == LabTables.ICCLAB ? 65280 : 65535) / range, 0, 65535);
        }
        var shift = Math.scalb(1.0, 16 - width);
        if (lab == LabTables.CIELAB) {
            return code(signed(value, width) * shift, -32768, 32767) & 0xFFFF;
        }
        return code(value * shift, 0, 65535);
    }

    /**
     * Rounds a code to the nearest integer within a range.
     *
     * @param value the code as a real, NaN rounding to the minimum
     * @param min   the smallest code
     * @param max   the largest code
     * @return the code
     */
    private static int code(double value, int min, int max) {
        if (!(value >= min)) {
            return min;
        }
        return value >= max ? max : (int) Math.floor(value + 0.5);
    }

    /**
     * Sign-extends a two's complement value.
     *
     * @param value the value in its low {@code width} bits
     * @param width the width in bits
     * @return the signed value
     */
    private static long signed(long value, int width) {
        return width == 64 ? value : value << (64 - width) >> (64 - width);
    }

    /**
     * Reads an unsigned value.
     *
     * @param src       the stored bytes
     * @param bit       the index of the value's first bit
     * @param width     the width in bits, from 1 to 64
     * @param bigEndian whether a whole-byte value is big-endian whatever the file's byte order
     * @return the value in the low {@code width} bits
     */
    private long unsigned(byte[] src, long bit, int width, boolean bigEndian) {
        var at = (int) (bit >>> 3);
        if ((bit & 7) == 0 && (width & 7) == 0) {
            var bytes = width >>> 3;
            var value = 0L;
            if (little && !bigEndian) {
                for (var i = bytes - 1; i >= 0; i--) {
                    value = value << 8 | (src[at + i] & 0xFF);
                }
            } else {
                for (var i = 0; i < bytes; i++) {
                    value = value << 8 | (src[at + i] & 0xFF);
                }
            }
            return value;
        }
        var value = 0L;
        var offset = (int) (bit & 7);
        var remaining = width;
        while (remaining > 0) {
            var available = 8 - offset;
            var take = Math.min(available, remaining);
            var piece = (src[at] & 0xFF) >>> (available - take) & ((1 << take) - 1);
            value = value << take | piece;
            remaining -= take;
            offset = 0;
            at++;
        }
        return value;
    }

    /**
     * Writes a 16-bit value in the file's byte order.
     *
     * @param dst   the destination
     * @param at    the index of the first byte
     * @param value the value, from 0 to 65535
     */
    private void put16(byte[] dst, int at, int value) {
        dst[at + (little ? 0 : 1)] = (byte) value;
        dst[at + (little ? 1 : 0)] = (byte) (value >>> 8);
    }

    /**
     * Writes a float in the file's byte order.
     *
     * @param dst   the destination
     * @param at    the index of the first byte
     * @param value the float to write
     */
    private void putFloat(byte[] dst, int at, float value) {
        var raw = Float.floatToRawIntBits(value);
        for (var i = 0; i < 4; i++) {
            dst[at + (little ? i : 3 - i)] = (byte) (raw >>> (8 * i));
        }
    }
}
