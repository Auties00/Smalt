package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Converts stored BMP rows of uncompressed or bit-field pixels to RGBA8.
 *
 * <p>Palette pixels look their index up in the color table (opaque black past its end). Masked
 * 16- and 32-bit pixels extract each channel with its mask and scale it to 8 bits: a mask of
 * {@code n <= 8} bits through a table holding {@code round(v * 255 / (2^n - 1))}, a wider mask by
 * keeping its top 8 bits; an absent color channel is 0 and an absent alpha channel 255.
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
     * The conversion selected for the file.
     */
    private final int kind;

    /**
     * The bits per pixel.
     */
    private final int bitCount;

    /**
     * The color table as packed little-endian RGBA pixels.
     */
    private final int[] palette;

    /**
     * The number of color table entries.
     */
    private final int paletteSize;

    /**
     * The red, green, blue and alpha masks.
     */
    private final int[] masks;

    /**
     * The right shift aligning each mask's lowest bit to bit 0.
     */
    private final int[] shifts;

    /**
     * The scaling table of each channel whose mask has at most 8 bits, or {@code null}.
     */
    private final byte[][] tables;

    /**
     * The extra right shift of each channel whose mask is wider than 8 bits.
     */
    private final int[] narrowing;

    /**
     * Whether the alpha channel is taken from the pixels.
     */
    private final boolean alpha;

    /**
     * Constructs the converter.
     *
     * @param kind the conversion
     * @param header the headers
     */
    private BmpChannels(int kind, BmpHeader header) {
        this.kind = kind;
        this.bitCount = header.bitCount();
        this.palette = header.palette();
        this.paletteSize = header.paletteSize();
        this.masks = header.masks();
        this.alpha = header.alphaMaskActive();
        this.shifts = new int[4];
        this.tables = new byte[4][];
        this.narrowing = new int[4];
        for (var channel = 0; channel < 4; channel++) {
            var mask = masks[channel];
            if (mask == 0) {
                continue;
            }
            shifts[channel] = Integer.numberOfTrailingZeros(mask);
            var bits = Integer.bitCount(mask);
            if (bits <= 8) {
                var maximum = (1 << bits) - 1;
                var table = new byte[maximum + 1];
                for (var value = 0; value <= maximum; value++) {
                    table[value] = (byte) ((value * 510 + maximum) / (2 * maximum));
                }
                tables[channel] = table;
            } else {
                narrowing[channel] = bits - 8;
            }
        }
    }

    /**
     * Creates the converter for a file.
     *
     * @param header the headers
     * @return the converter
     */
    static BmpChannels of(BmpHeader header) {
        var masks = header.masks();
        var kind = switch (header.bitCount()) {
            case 24 -> BGR24;
            case 16 -> MASKED;
            case 32 -> masks[0] == 0x00FF_0000 && masks[1] == 0x0000_FF00 && masks[2] == 0xFF && (masks[3] == 0 || masks[3] == 0xFF00_0000)
                    ? BGRA32 : MASKED;
            default -> PALETTE;
        };
        return new BmpChannels(kind, header);
    }

    /**
     * Converts the first pixels of a stored row.
     *
     * @param row the stored row
     * @param count the number of pixels to convert, at most the row's
     * @param pixels the RGBA canvas
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
            default -> convertMasked(row, count, pixels, offset);
        };
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
            ByteArrays.put32le(pixels, offset + x * 4, index < paletteSize ? palette[index] : 0xFF00_0000);
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
     * Converts 16- or 32-bit pixels with arbitrary masks.
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
     * Extracts and scales one channel of a masked pixel.
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
        return table != null ? table[raw] : (byte) (raw >>> narrowing[channel]);
    }
}
