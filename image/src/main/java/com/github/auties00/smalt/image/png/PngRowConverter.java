package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Converts reconstructed PNG scanlines of any color type and bit depth to packed RGBA8.
 *
 * <p>Samples of fewer than 8 bits (grayscale only) are scaled to 8 bits by bit replication, which
 * for these depths equals the exact scaling {@code v * 255 / (2^depth - 1)} of PNG 3rd edition
 * 13.12. Samples of 16 bits are reduced with exact rounding, {@code (v * 255 + 32767) / 65535},
 * the nearest 8-bit value to {@code v * 255 / 65535} (no ties occur). Grayscale and truecolor
 * images with a {@code tRNS} chunk get alpha 0 on pixels whose samples equal the key at the
 * image's full bit depth, and 255 elsewhere; indexed-color images take alpha from the
 * {@code tRNS} entries, 255 beyond them.
 *
 * <p>Indexed-color and low-depth grayscale pixels go through a 256-entry table of packed pixels.
 * An index beyond the palette makes {@link #convert(byte[], int, int, byte[], int, int)} raise
 * {@link MediaException.Malformed}, as libpng does.
 */
final class PngRowConverter {
    /**
     * Pixels looked up in {@link #table} by an index or gray level of up to 8 bits.
     */
    private static final int TABLE = 0;

    /**
     * 8-bit truecolor without a transparency key.
     */
    private static final int RGB8 = 1;

    /**
     * 8-bit truecolor with a transparency key.
     */
    private static final int RGB8_KEY = 2;

    /**
     * 8-bit truecolor with alpha.
     */
    private static final int RGBA8 = 3;

    /**
     * 8-bit grayscale with alpha.
     */
    private static final int GRAY_ALPHA8 = 4;

    /**
     * 16-bit grayscale, optionally with a transparency key.
     */
    private static final int GRAY16 = 5;

    /**
     * 16-bit truecolor, optionally with a transparency key.
     */
    private static final int RGB16 = 6;

    /**
     * 16-bit grayscale with alpha.
     */
    private static final int GRAY_ALPHA16 = 7;

    /**
     * 16-bit truecolor with alpha.
     */
    private static final int RGBA16 = 8;

    /**
     * The conversion selected for the image.
     */
    private final int kind;

    /**
     * The bits per table index, for {@link #TABLE} conversions.
     */
    private final int indexBits;

    /**
     * The packed little-endian RGBA pixel of every index, for {@link #TABLE} conversions.
     */
    private final int[] table;

    /**
     * The number of valid indices; indices at or above it are rejected.
     */
    private final int tableSize;

    /**
     * Whether a transparency key applies, for truecolor and 16-bit grayscale conversions.
     */
    private final boolean keyed;

    /**
     * The red (or gray) sample of the transparency key, at the image's bit depth.
     */
    private final int keyRed;

    /**
     * The green sample of the transparency key, at the image's bit depth.
     */
    private final int keyGreen;

    /**
     * The blue sample of the transparency key, at the image's bit depth.
     */
    private final int keyBlue;

    /**
     * Constructs a converter.
     *
     * @param kind the conversion
     * @param indexBits the bits per table index
     * @param table the table, or {@code null}
     * @param tableSize the number of valid indices
     * @param keyed whether a transparency key applies
     * @param keyRed the red or gray key sample
     * @param keyGreen the green key sample
     * @param keyBlue the blue key sample
     */
    private PngRowConverter(int kind, int indexBits, int[] table, int tableSize, boolean keyed, int keyRed,
                            int keyGreen, int keyBlue) {
        this.kind = kind;
        this.indexBits = indexBits;
        this.table = table;
        this.tableSize = tableSize;
        this.keyed = keyed;
        this.keyRed = keyRed;
        this.keyGreen = keyGreen;
        this.keyBlue = keyBlue;
    }

    /**
     * Creates the converter for an image.
     *
     * @param header the image header
     * @param palette the palette as packed little-endian RGBA pixels (alpha already taken
     * from {@code tRNS}), or {@code null} for images without a palette
     * @param paletteSize the number of palette entries
     * @param transparency the {@code tRNS} key samples for grayscale (one value) or truecolor
     * (three values) images, or {@code null} when absent
     * @return the converter
     */
    static PngRowConverter of(PngHeader header, int[] palette, int paletteSize, int[] transparency) {
        var depth = header.bitDepth();
        return switch (header.colorType()) {
            case PngHeader.PALETTE -> new PngRowConverter(TABLE, depth, palette, paletteSize, false, 0, 0, 0);
            case PngHeader.GRAY -> {
                if (depth == 16) {
                    var keyed = transparency != null;
                    yield new PngRowConverter(GRAY16, 0, null, 0, keyed, keyed ? transparency[0] : 0, 0, 0);
                }
                var levels = 1 << depth;
                var gray = new int[256];
                for (var level = 0; level < levels; level++) {
                    var value = level * 255 / (levels - 1);
                    var alpha = transparency != null && transparency[0] == level ? 0 : 0xFF;
                    gray[level] = pack(value, value, value, alpha);
                }
                yield new PngRowConverter(TABLE, depth, gray, levels, false, 0, 0, 0);
            }
            case PngHeader.RGB -> {
                var keyed = transparency != null;
                var red = keyed ? transparency[0] : 0;
                var green = keyed ? transparency[1] : 0;
                var blue = keyed ? transparency[2] : 0;
                if (depth == 16) {
                    yield new PngRowConverter(RGB16, 0, null, 0, keyed, red, green, blue);
                }
                yield new PngRowConverter(keyed ? RGB8_KEY : RGB8, 0, null, 0, keyed, red, green, blue);
            }
            case PngHeader.GRAY_ALPHA ->
                    new PngRowConverter(depth == 16 ? GRAY_ALPHA16 : GRAY_ALPHA8, 0, null, 0, false, 0, 0, 0);
            default -> new PngRowConverter(depth == 16 ? RGBA16 : RGBA8, 0, null, 0, false, 0, 0, 0);
        };
    }

    /**
     * Packs four 8-bit samples into the little-endian {@code int} whose bytes are R, G, B, A.
     *
     * @param red the red sample
     * @param green the green sample
     * @param blue the blue sample
     * @param alpha the alpha sample
     * @return the packed pixel
     */
    static int pack(int red, int green, int blue, int alpha) {
        return (red & 0xFF) | (green & 0xFF) << 8 | (blue & 0xFF) << 16 | (alpha & 0xFF) << 24;
    }

    /**
     * Reduces a 16-bit sample to 8 bits with exact rounding.
     *
     * @param value the sample, from 0 to 65535
     * @return {@code (value * 255 + 32767) / 65535}
     * @implNote This implementation evaluates the equivalent {@code (value * 255 + 32895) >>> 16},
     * which a unit test checks against the division for all 65536 inputs.
     */
    static int reduce16(int value) {
        return (value * 255 + 32895) >>> 16;
    }

    /**
     * Converts {@code count} pixels of a reconstructed scanline to RGBA8.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first byte of the scanline
     * @param count the number of pixels to convert
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between consecutive destination pixels, a multiple of 4
     * @throws MediaException.Malformed if an indexed-color pixel refers past the palette
     */
    void convert(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset, int targetStep) {
        switch (kind) {
            case TABLE -> convertTable(source, sourceOffset, count, target, targetOffset, targetStep);
            case RGB8 -> {
                if (targetStep == 4) {
                    PngFilterKernel.INSTANCE.expandRgb(source, sourceOffset, target, targetOffset, count);
                } else {
                    convertRgb8(source, sourceOffset, count, target, targetOffset, targetStep);
                }
            }
            case RGB8_KEY -> convertRgb8Keyed(source, sourceOffset, count, target, targetOffset, targetStep);
            case RGBA8 -> convertRgba8(source, sourceOffset, count, target, targetOffset, targetStep);
            case GRAY_ALPHA8 -> convertGrayAlpha8(source, sourceOffset, count, target, targetOffset, targetStep);
            case GRAY16 -> convertGray16(source, sourceOffset, count, target, targetOffset, targetStep);
            case RGB16 -> convertRgb16(source, sourceOffset, count, target, targetOffset, targetStep);
            case GRAY_ALPHA16 -> convertGrayAlpha16(source, sourceOffset, count, target, targetOffset, targetStep);
            default -> convertRgba16(source, sourceOffset, count, target, targetOffset, targetStep);
        }
    }

    /**
     * Converts {@code count} pixels of a reconstructed 16-bit scanline to packed 16-bit RGBA,
     * keeping every sample at full precision.
     *
     * <p>Grayscale is replicated to red, green and blue; images without alpha get alpha 65535, or
     * 0 on pixels whose samples equal the {@code tRNS} key.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first byte of the scanline
     * @param count the number of pixels to convert
     * @param target the destination
     * @param targetOffset the index of the first destination sample
     * @param targetStep the sample distance between consecutive destination pixels, a multiple
     * of 4
     * @throws IllegalStateException if the image does not have 16-bit samples
     */
    void convertWide(byte[] source, int sourceOffset, int count, short[] target, int targetOffset, int targetStep) {
        for (var i = 0; i < count; i++) {
            var t = targetOffset + i * targetStep;
            switch (kind) {
                case GRAY16 -> {
                    var gray = ByteArrays.u16be(source, sourceOffset + i * 2);
                    put(target, t, gray, gray, gray, keyed && gray == keyRed ? 0 : 0xFFFF);
                }
                case RGB16 -> {
                    var s = sourceOffset + i * 6;
                    var r = ByteArrays.u16be(source, s);
                    var g = ByteArrays.u16be(source, s + 2);
                    var b = ByteArrays.u16be(source, s + 4);
                    put(target, t, r, g, b, keyed && r == keyRed && g == keyGreen && b == keyBlue ? 0 : 0xFFFF);
                }
                case GRAY_ALPHA16 -> {
                    var s = sourceOffset + i * 4;
                    var gray = ByteArrays.u16be(source, s);
                    put(target, t, gray, gray, gray, ByteArrays.u16be(source, s + 2));
                }
                case RGBA16 -> {
                    var s = sourceOffset + i * 8;
                    put(target, t, ByteArrays.u16be(source, s), ByteArrays.u16be(source, s + 2),
                            ByteArrays.u16be(source, s + 4), ByteArrays.u16be(source, s + 6));
                }
                default -> throw new IllegalStateException("not a 16-bit PNG conversion");
            }
        }
    }

    /**
     * Stores one 16-bit RGBA pixel.
     *
     * @param target the destination
     * @param offset the index of the red sample
     * @param red the red sample
     * @param green the green sample
     * @param blue the blue sample
     * @param alpha the alpha sample
     */
    private static void put(short[] target, int offset, int red, int green, int blue, int alpha) {
        target[offset] = (short) red;
        target[offset + 1] = (short) green;
        target[offset + 2] = (short) blue;
        target[offset + 3] = (short) alpha;
    }

    /**
     * Converts pixels through {@link #table}.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     * @throws MediaException.Malformed if an index is at or above {@link #tableSize}
     */
    private void convertTable(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                              int targetStep) {
        var lookup = table;
        var limit = tableSize - 1;
        var invalid = 0;
        if (indexBits == 8) {
            for (var i = 0; i < count; i++) {
                var index = source[sourceOffset + i] & 0xFF;
                invalid |= limit - index;
                ByteArrays.put32le(target, targetOffset + i * targetStep, lookup[index]);
            }
        } else {
            var bits = indexBits;
            var perByte = 8 / bits;
            var mask = (1 << bits) - 1;
            for (var i = 0; i < count; i++) {
                var packed = source[sourceOffset + i / perByte] & 0xFF;
                var shift = 8 - bits - (i % perByte) * bits;
                var index = (packed >>> shift) & mask;
                invalid |= limit - index;
                ByteArrays.put32le(target, targetOffset + i * targetStep, lookup[index]);
            }
        }
        if (invalid < 0) {
            throw new MediaException.Malformed("PNG pixel refers past the " + tableSize + "-entry palette");
        }
    }

    /**
     * Converts 8-bit truecolor pixels.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private static void convertRgb8(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                                    int targetStep) {
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 3;
            var t = targetOffset + i * targetStep;
            target[t] = source[s];
            target[t + 1] = source[s + 1];
            target[t + 2] = source[s + 2];
            target[t + 3] = (byte) 0xFF;
        }
    }

    /**
     * Converts 8-bit truecolor pixels with a transparency key.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private void convertRgb8Keyed(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                                  int targetStep) {
        var red = keyRed;
        var green = keyGreen;
        var blue = keyBlue;
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 3;
            var r = source[s] & 0xFF;
            var g = source[s + 1] & 0xFF;
            var b = source[s + 2] & 0xFF;
            var alpha = r == red && g == green && b == blue ? 0 : 0xFF;
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(r, g, b, alpha));
        }
    }

    /**
     * Copies 8-bit truecolor with alpha pixels.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private static void convertRgba8(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                                     int targetStep) {
        if (targetStep == 4) {
            System.arraycopy(source, sourceOffset, target, targetOffset, count * 4);
            return;
        }
        for (var i = 0; i < count; i++) {
            ByteArrays.put32le(target, targetOffset + i * targetStep, ByteArrays.i32le(source, sourceOffset + i * 4));
        }
    }

    /**
     * Converts 8-bit grayscale with alpha pixels.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private static void convertGrayAlpha8(byte[] source, int sourceOffset, int count, byte[] target,
                                          int targetOffset, int targetStep) {
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 2;
            var gray = source[s] & 0xFF;
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(gray, gray, gray, source[s + 1]));
        }
    }

    /**
     * Converts 16-bit grayscale pixels, applying the transparency key when present.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private void convertGray16(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                               int targetStep) {
        var key = keyed ? keyRed : -1;
        for (var i = 0; i < count; i++) {
            var sample = ByteArrays.u16be(source, sourceOffset + i * 2);
            var gray = reduce16(sample);
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(gray, gray, gray, sample == key ? 0 : 0xFF));
        }
    }

    /**
     * Converts 16-bit truecolor pixels, applying the transparency key when present.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private void convertRgb16(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                              int targetStep) {
        var checkKey = keyed;
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 6;
            var r = ByteArrays.u16be(source, s);
            var g = ByteArrays.u16be(source, s + 2);
            var b = ByteArrays.u16be(source, s + 4);
            var alpha = checkKey && r == keyRed && g == keyGreen && b == keyBlue ? 0 : 0xFF;
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(reduce16(r), reduce16(g), reduce16(b), alpha));
        }
    }

    /**
     * Converts 16-bit grayscale with alpha pixels.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private static void convertGrayAlpha16(byte[] source, int sourceOffset, int count, byte[] target,
                                           int targetOffset, int targetStep) {
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 4;
            var gray = reduce16(ByteArrays.u16be(source, s));
            var alpha = reduce16(ByteArrays.u16be(source, s + 2));
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(gray, gray, gray, alpha));
        }
    }

    /**
     * Converts 16-bit truecolor with alpha pixels.
     *
     * @param source the scanline
     * @param sourceOffset the index of the first scanline byte
     * @param count the number of pixels
     * @param target the destination
     * @param targetOffset the index of the first destination pixel
     * @param targetStep the byte distance between destination pixels
     */
    private static void convertRgba16(byte[] source, int sourceOffset, int count, byte[] target, int targetOffset,
                                      int targetStep) {
        for (var i = 0; i < count; i++) {
            var s = sourceOffset + i * 8;
            var r = reduce16(ByteArrays.u16be(source, s));
            var g = reduce16(ByteArrays.u16be(source, s + 2));
            var b = reduce16(ByteArrays.u16be(source, s + 4));
            var a = reduce16(ByteArrays.u16be(source, s + 6));
            ByteArrays.put32le(target, targetOffset + i * targetStep, pack(r, g, b, a));
        }
    }
}
