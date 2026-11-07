package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.image.bmp.internal.BmpHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Decodes {@code BI_RLE8}, {@code BI_RLE4} (and their CMYK variants) and OS/2 RLE24 pixel data.
 *
 * <p>The data is a sequence of codes, starting at the bottom-left pixel:
 * <ul>
 *   <li>{@code n c} with {@code n > 0}: an encoded run of {@code n} pixels, all of index
 *       {@code c} (RLE8) or alternating the high and low nibble of {@code c} (RLE4); for RLE24
 *       {@code n} is followed by the three bytes (blue, green, red) of the run's color;</li>
 *   <li>{@code 0 0}: end of line, continue at the start of the next row up;</li>
 *   <li>{@code 0 1}: end of bitmap;</li>
 *   <li>{@code 0 2 dx dy}: move right by {@code dx} and up by {@code dy};</li>
 *   <li>{@code 0 n} with {@code n >= 3}: an absolute run of {@code n} indices (bytes for RLE8,
 *       nibbles for RLE4) or of {@code n} three-byte colors (RLE24), padded to an even number of
 *       bytes.</li>
 * </ul>
 * Pixels past the right edge are clipped, pixels no code sets stay zero (transparent, or no ink
 * for a CMYK color table), and decoding stops at the end of bitmap, above the top row, or when
 * the data runs out.
 */
final class BmpRunLength {
    /**
     * Prevents instantiation.
     */
    private BmpRunLength() {
        throw new AssertionError();
    }

    /**
     * Decodes run-length data onto a zero-filled canvas.
     *
     * @param data the compressed data
     * @param length the number of valid bytes in {@code data}
     * @param header the validated headers
     * @param pixels the zero-filled RGBA canvas
     * @return the number of pixels set
     */
    static long decode(byte[] data, int length, BmpHeader header, byte[] pixels) {
        if (header.compression() == BmpHeader.RLE24) {
            return decodeRle24(data, length, header, pixels);
        }
        var width = header.width();
        var height = header.height();
        var palette = header.palette();
        var paletteSize = header.paletteSize();
        var missing = header.isCmyk() ? 0 : 0xFF00_0000;
        var four = header.compression() == BmpHeader.RLE4 || header.compression() == BmpHeader.CMYK_RLE4;
        var x = 0L;
        var y = 0;
        var position = 0;
        var written = 0L;
        while (y < height && position + 2 <= length) {
            var count = data[position] & 0xFF;
            var value = data[position + 1] & 0xFF;
            position += 2;
            if (count > 0) {
                if (x < width) {
                    var visible = (int) Math.min(count, width - x);
                    for (var i = 0; i < visible; i++) {
                        var index = four ? ((i & 1) == 0 ? value >>> 4 : value & 0xF) : value;
                        set(pixels, width, height, (int) x + i, y, index < paletteSize ? palette[index] : missing);
                    }
                    written += visible;
                }
                x += count;
                continue;
            }
            switch (value) {
                case 0 -> {
                    x = 0;
                    y++;
                }
                case 1 -> {
                    return written;
                }
                case 2 -> {
                    if (position + 2 > length) {
                        return written;
                    }
                    x += data[position] & 0xFF;
                    y += data[position + 1] & 0xFF;
                    position += 2;
                }
                default -> {
                    var bytes = four ? (value + 1) / 2 : value;
                    if (x < width) {
                        var visible = (int) Math.min(value, width - x);
                        for (var i = 0; i < visible; i++) {
                            var at = position + (four ? i / 2 : i);
                            if (at >= length) {
                                return written;
                            }
                            var packed = data[at] & 0xFF;
                            var index = four ? ((i & 1) == 0 ? packed >>> 4 : packed & 0xF) : packed;
                            set(pixels, width, height, (int) x + i, y, index < paletteSize ? palette[index] : missing);
                            written++;
                        }
                    }
                    x += value;
                    position += (bytes + 1) & ~1;
                }
            }
        }
        return written;
    }

    /**
     * Decodes RLE24 data onto a zero-filled canvas.
     *
     * @param data the compressed data
     * @param length the number of valid bytes in {@code data}
     * @param header the validated headers
     * @param pixels the zero-filled RGBA canvas
     * @return the number of pixels set
     */
    private static long decodeRle24(byte[] data, int length, BmpHeader header, byte[] pixels) {
        var width = header.width();
        var height = header.height();
        var x = 0L;
        var y = 0;
        var position = 0;
        var written = 0L;
        while (y < height && position + 2 <= length) {
            var count = data[position] & 0xFF;
            var value = data[position + 1] & 0xFF;
            if (count > 0) {
                if (position + 4 > length) {
                    return written;
                }
                var color = (data[position + 3] & 0xFF) | (data[position + 2] & 0xFF) << 8
                        | (data[position + 1] & 0xFF) << 16 | 0xFF00_0000;
                position += 4;
                if (x < width) {
                    var visible = (int) Math.min(count, width - x);
                    for (var i = 0; i < visible; i++) {
                        set(pixels, width, height, (int) x + i, y, color);
                    }
                    written += visible;
                }
                x += count;
                continue;
            }
            position += 2;
            switch (value) {
                case 0 -> {
                    x = 0;
                    y++;
                }
                case 1 -> {
                    return written;
                }
                case 2 -> {
                    if (position + 2 > length) {
                        return written;
                    }
                    x += data[position] & 0xFF;
                    y += data[position + 1] & 0xFF;
                    position += 2;
                }
                default -> {
                    if (x < width) {
                        var visible = (int) Math.min(value, width - x);
                        for (var i = 0; i < visible; i++) {
                            var at = position + i * 3;
                            if (at + 3 > length) {
                                return written;
                            }
                            var color = (data[at + 2] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at] & 0xFF) << 16
                                    | 0xFF00_0000;
                            set(pixels, width, height, (int) x + i, y, color);
                            written++;
                        }
                    }
                    x += value;
                    position += (value * 3 + 1) & ~1;
                }
            }
        }
        return written;
    }

    /**
     * Writes one pixel, counting rows from the bottom.
     *
     * @param pixels the canvas
     * @param width the canvas width
     * @param height the canvas height
     * @param x the column
     * @param y the row counted from the bottom
     * @param color the packed little-endian RGBA pixel
     */
    private static void set(byte[] pixels, int width, int height, int x, int y, int color) {
        ByteArrays.put32le(pixels, ((height - 1 - y) * width + x) * 4, color);
    }
}
