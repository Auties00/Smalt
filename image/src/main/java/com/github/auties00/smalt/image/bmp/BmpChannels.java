package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Converts stored BMP rows of uncompressed or bit-field pixels to 8-bit RGBA or CMYK, or to 16-bit RGBA.
 *
 * <p>Palette pixels look their index up in the color table (opaque black past its end, or no ink
 * for a CMYK table). Masked 16- and 32-bit pixels extract each channel with its mask and scale a
 * value {@code v} of {@code n} bits to {@code round(v * max / (2^n - 1))}, {@code max} being 255 or
 * 65535, through a table for masks of up to 16 bits; an absent color channel is 0 and an absent alpha
 * channel opaque. 64-bit pixels hold linear-light s2.13 fixed-point
 * values (8192 is 1.0) in blue, green, red, alpha order: the color channels are clamped to 0..1 and
 * encoded with the sRGB transfer function, alpha is clamped and scaled.
 */
final class BmpChannels {
    /**
     * Palette pixels of 1, 2, 4 or 8 bits.
     */
    private static final int PALETTE = 0;

    /**
     * 24-bit blue, green, red pixels.
     */
    private static final int BGR24 = 1;

    /**
     * 32-bit blue, green, red, alpha pixels (the default {@code BI_RGB} masks).
     */
    private static final int BGRA32 = 2;

    /**
     * 16- or 32-bit pixels with arbitrary masks.
     */
    private static final int MASKED = 3;

    /**
     * 32-bit CMYK pixels stored black, yellow, magenta, cyan.
     */
    private static final int CMYK32 = 4;

    /**
     * 64-bit linear blue, green, red, alpha pixels.
     */
    private static final int BGRA64 = 5;

    /**
     * The value of 1.0 in s2.13 fixed point.
     */
    private static final int ONE = 8192;

    /**
     * The conversion selected for the file.
     */
    private final int kind;

    /**
     * The bits per pixel.
     */
    private final int bitCount;

    /**
     * The color table as packed little-endian pixels.
     */
    private final int[] palette;

    /**
     * The number of color table entries.
     */
    private final int paletteSize;

    /**
     * The value of an index past the color table.
     */
    private final int missing;

    /**
     * The red, green, blue and alpha masks.
     */
    private final int[] masks;

    /**
     * The right shift aligning each mask's lowest bit to bit 0.
     */
    private final int[] shifts;

    /**
     * The 8-bit scaling table of each channel whose mask has at most 16 bits, or {@code null}; built for
     * the 8-bit output only.
     */
    private final byte[][] tables;

    /**
     * The 16-bit scaling table of each channel whose mask has at most 16 bits, or {@code null}; built for
     * the 16-bit output only.
     */
    private final short[][] wideTables;

    /**
     * The largest value of each channel's mask, {@code 2^n - 1}.
     */
    private final long[] maxima;

    /**
     * Whether the alpha channel is taken from the pixels.
     */
    private final boolean alpha;

    /**
     * Constructs the converter.
     *
     * @param kind the conversion
     * @param header the headers
     * @param wide whether the output has 16 bits per sample
     */
    private BmpChannels(int kind, BmpHeader header, boolean wide) {
        this.kind = kind;
        this.bitCount = header.bitCount();
        this.palette = header.palette();
        this.paletteSize = header.paletteSize();
        this.missing = header.isCmyk() ? 0 : 0xFF00_0000;
        this.masks = header.masks();
        this.alpha = header.alphaMaskActive();
        this.shifts = new int[4];
        this.tables = new byte[4][];
        this.wideTables = new short[4][];
        this.maxima = new long[4];
        for (var channel = 0; channel < 4; channel++) {
            var mask = masks[channel];
            if (mask == 0) {
                continue;
            }
            shifts[channel] = Integer.numberOfTrailingZeros(mask);
            var bits = Integer.bitCount(mask);
            maxima[channel] = (1L << bits) - 1;
            if (bits > 16) {
                continue;
            }
            var maximum = (1 << bits) - 1;
            if (wide) {
                var table = new short[maximum + 1];
                for (var value = 0; value <= maximum; value++) {
                    table[value] = (short) (((long) value * 131070 + maximum) / (2L * maximum));
                }
                wideTables[channel] = table;
            } else {
                var table = new byte[maximum + 1];
                for (var value = 0; value <= maximum; value++) {
                    table[value] = (byte) ((value * 510 + maximum) / (2 * maximum));
                }
                tables[channel] = table;
            }
        }
    }

    /**
     * Creates the converter for a file.
     *
     * @param header the headers
     * @param wide whether the output has 16 bits per sample, for masks wider than 8 bits and 64-bit pixels
     * @return the converter
     */
    static BmpChannels of(BmpHeader header, boolean wide) {
        var masks = header.masks();
        var kind = switch (header.bitCount()) {
            case 24 -> BGR24;
            case 16 -> MASKED;
            case 32 -> header.isCmyk() ? CMYK32 : !wide && masks[0] == 0x00FF_0000 && masks[1] == 0x0000_FF00
                    && masks[2] == 0xFF && (masks[3] == 0 || masks[3] == 0xFF00_0000) ? BGRA32 : MASKED;
            case 64 -> BGRA64;
            default -> PALETTE;
        };
        return new BmpChannels(kind, header, wide);
    }

    /**
     * Converts the first pixels of a stored row to 8-bit RGBA, or CMYK for a CMYK file.
     *
     * @param row the stored row
     * @param count the number of pixels to convert, at most the row's
     * @param pixels the destination canvas, four bytes per pixel
     * @param offset the index of the destination row's first byte
     * @return a value that is non-zero when any converted pixel has a non-zero stored alpha
     */
    int convert(byte[] row, int count, byte[] pixels, int offset) {
        return switch (kind) {
            case PALETTE -> {
                convertPalette(row, count, pixels, offset);
                yield 0;
            }
            case BGR24 -> {
                convertBgr(row, count, pixels, offset);
                yield 0;
            }
            case BGRA32 -> convertBgra(row, count, pixels, offset);
            case CMYK32 -> {
                convertCmyk(row, count, pixels, offset);
                yield 0;
            }
            case BGRA64 -> convertBgra64(row, count, pixels, offset);
            default -> convertMasked(row, count, pixels, offset);
        };
    }

    /**
     * Converts the first pixels of a stored row of masked or 64-bit pixels to 16-bit RGBA.
     *
     * @param row the stored row
     * @param count the number of pixels to convert, at most the row's
     * @param pixels the destination canvas, four samples per pixel
     * @param offset the index of the destination row's first sample
     * @return a value that is non-zero when any converted pixel has a non-zero stored alpha
     */
    int convertWide(byte[] row, int count, short[] pixels, int offset) {
        return kind == BGRA64 ? convertBgra64Wide(row, count, pixels, offset) : convertMaskedWide(row, count, pixels,
                offset);
    }

    /**
     * Converts palette pixels.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     */
    private void convertPalette(byte[] row, int count, byte[] pixels, int offset) {
        var bits = bitCount;
        var perByte = 8 / bits;
        var mask = (1 << bits) - 1;
        for (var x = 0; x < count; x++) {
            var index = bits == 8 ? row[x] & 0xFF : ((row[x / perByte] & 0xFF) >>> (8 - bits - (x % perByte) * bits)) & mask;
            ByteArrays.put32le(pixels, offset + x * 4, index < paletteSize ? palette[index] : missing);
        }
    }

    /**
     * Converts 24-bit pixels.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     */
    private static void convertBgr(byte[] row, int count, byte[] pixels, int offset) {
        for (var x = 0; x < count; x++) {
            var s = x * 3;
            var t = offset + x * 4;
            pixels[t] = row[s + 2];
            pixels[t + 1] = row[s + 1];
            pixels[t + 2] = row[s];
            pixels[t + 3] = (byte) 0xFF;
        }
    }

    /**
     * Converts 32-bit CMYK pixels, stored black, yellow, magenta, cyan, to cyan, magenta, yellow, black.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     */
    private static void convertCmyk(byte[] row, int count, byte[] pixels, int offset) {
        for (var x = 0; x < count; x++) {
            var s = x * 4;
            var t = offset + x * 4;
            pixels[t] = row[s + 3];
            pixels[t + 1] = row[s + 2];
            pixels[t + 2] = row[s + 1];
            pixels[t + 3] = row[s];
        }
    }

    /**
     * Converts 32-bit pixels with the default masks.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     * @return the OR of the stored alpha bytes, or 0 when alpha is not in use
     */
    private int convertBgra(byte[] row, int count, byte[] pixels, int offset) {
        var seen = 0;
        var useAlpha = alpha;
        for (var x = 0; x < count; x++) {
            var s = x * 4;
            var t = offset + x * 4;
            pixels[t] = row[s + 2];
            pixels[t + 1] = row[s + 1];
            pixels[t + 2] = row[s];
            var a = useAlpha ? row[s + 3] : (byte) 0xFF;
            pixels[t + 3] = a;
            seen |= a;
        }
        return useAlpha ? seen & 0xFF : 0;
    }

    /**
     * Converts 64-bit pixels to 8 bits.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     * @return the OR of the stored alpha values
     */
    private static int convertBgra64(byte[] row, int count, byte[] pixels, int offset) {
        var encode = Linear.SRGB8;
        var seen = 0;
        for (var x = 0; x < count; x++) {
            var s = x * 8;
            var t = offset + x * 4;
            pixels[t] = encode[clamp(ByteArrays.u16le(row, s + 4))];
            pixels[t + 1] = encode[clamp(ByteArrays.u16le(row, s + 2))];
            pixels[t + 2] = encode[clamp(ByteArrays.u16le(row, s))];
            var a = clamp(ByteArrays.u16le(row, s + 6));
            pixels[t + 3] = (byte) ((a * 510 + ONE) / (2 * ONE));
            seen |= a;
        }
        return seen;
    }

    /**
     * Converts 64-bit pixels to 16 bits.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     * @return the OR of the stored alpha values
     */
    private static int convertBgra64Wide(byte[] row, int count, short[] pixels, int offset) {
        var encode = Linear.SRGB16;
        var seen = 0;
        for (var x = 0; x < count; x++) {
            var s = x * 8;
            var t = offset + x * 4;
            pixels[t] = encode[clamp(ByteArrays.u16le(row, s + 4))];
            pixels[t + 1] = encode[clamp(ByteArrays.u16le(row, s + 2))];
            pixels[t + 2] = encode[clamp(ByteArrays.u16le(row, s))];
            var a = clamp(ByteArrays.u16le(row, s + 6));
            pixels[t + 3] = (short) (((long) a * 131070 + ONE) / (2L * ONE));
            seen |= a;
        }
        return seen;
    }

    /**
     * Clamps a stored s2.13 value to 0..1.
     *
     * @param stored the unsigned 16-bit field
     * @return the value, from 0 to {@link #ONE}
     */
    private static int clamp(int stored) {
        var value = (short) stored;
        return value < 0 ? 0 : Math.min(value, ONE);
    }

    /**
     * Converts 16- or 32-bit pixels with arbitrary masks to 8 bits.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     * @return the OR of the stored alpha values, or 0 when alpha is not in use
     */
    private int convertMasked(byte[] row, int count, byte[] pixels, int offset) {
        var seen = 0;
        for (var x = 0; x < count; x++) {
            var value = bitCount == 16 ? ByteArrays.u16le(row, x * 2) : ByteArrays.i32le(row, x * 4);
            var t = offset + x * 4;
            pixels[t] = channel(value, 0);
            pixels[t + 1] = channel(value, 1);
            pixels[t + 2] = channel(value, 2);
            if (alpha) {
                seen |= value & masks[3];
                pixels[t + 3] = channel(value, 3);
            } else {
                pixels[t + 3] = (byte) 0xFF;
            }
        }
        return seen;
    }

    /**
     * Converts 16- or 32-bit pixels with arbitrary masks to 16 bits.
     *
     * @param row the stored row
     * @param count the number of pixels
     * @param pixels the canvas
     * @param offset the index of the destination row
     * @return the OR of the stored alpha values, or 0 when alpha is not in use
     */
    private int convertMaskedWide(byte[] row, int count, short[] pixels, int offset) {
        var seen = 0;
        for (var x = 0; x < count; x++) {
            var value = bitCount == 16 ? ByteArrays.u16le(row, x * 2) : ByteArrays.i32le(row, x * 4);
            var t = offset + x * 4;
            pixels[t] = wideChannel(value, 0);
            pixels[t + 1] = wideChannel(value, 1);
            pixels[t + 2] = wideChannel(value, 2);
            if (alpha) {
                seen |= value & masks[3];
                pixels[t + 3] = wideChannel(value, 3);
            } else {
                pixels[t + 3] = (short) 0xFFFF;
            }
        }
        return seen;
    }

    /**
     * Extracts and scales one channel of a masked pixel to 8 bits.
     *
     * @param value the stored pixel
     * @param channel the channel: 0 red, 1 green, 2 blue, 3 alpha
     * @return the 8-bit channel value
     */
    private byte channel(int value, int channel) {
        var mask = masks[channel];
        if (mask == 0) {
            return 0;
        }
        var raw = (value & mask) >>> shifts[channel];
        var table = tables[channel];
        if (table != null) {
            return table[raw];
        }
        var maximum = maxima[channel];
        return (byte) (((raw & 0xFFFF_FFFFL) * 510 + maximum) / (2 * maximum));
    }

    /**
     * Extracts and scales one channel of a masked pixel to 16 bits.
     *
     * @param value the stored pixel
     * @param channel the channel: 0 red, 1 green, 2 blue, 3 alpha
     * @return the 16-bit channel value
     */
    private short wideChannel(int value, int channel) {
        var mask = masks[channel];
        if (mask == 0) {
            return 0;
        }
        var raw = (value & mask) >>> shifts[channel];
        var table = wideTables[channel];
        if (table != null) {
            return table[raw];
        }
        var maximum = maxima[channel];
        return (short) (((raw & 0xFFFF_FFFFL) * 131070 + maximum) / (2 * maximum));
    }

    /**
     * Holds the sRGB encoding of every clamped s2.13 linear value, built on first use.
     */
    private static final class Linear {
        /**
         * The 8-bit encodings, indexed by the value from 0 to {@link #ONE}.
         */
        static final byte[] SRGB8 = new byte[ONE + 1];

        /**
         * The 16-bit encodings, indexed by the value from 0 to {@link #ONE}.
         */
        static final short[] SRGB16 = new short[ONE + 1];

        static {
            for (var i = 0; i <= ONE; i++) {
                var linear = (double) i / ONE;
                var encoded = linear <= 0.0031308 ? 12.92 * linear : 1.055 * StrictMath.pow(linear, 1 / 2.4) - 0.055;
                SRGB8[i] = (byte) Math.round(encoded * 255);
                SRGB16[i] = (short) Math.round(encoded * 65535);
            }
        }

        /**
         * Prevents instantiation.
         */
        private Linear() {
            throw new AssertionError();
        }
    }
}
