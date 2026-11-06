/* Ports the YUV to RGB conversions of DirectXTex (DirectXTexConvert.cpp, commit c0cb977). Copyright (c) Microsoft
   Corporation; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.dds.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;

/**
 * The pixel format of a DDS surface and how its texels decode.
 *
 * <p>A format is block-compressed ({@link Kind#BC1} to {@link Kind#BC7}), a 4:2:2 packed RGB pair format
 * ({@link Kind#RGBG}, {@link Kind#GRGB}), a YUV format ({@link Kind#YUV444}, {@link Kind#YUV422},
 * {@link Kind#PLANAR}), a 1-bit format ({@link Kind#BITS}), a legacy palette format
 * ({@link Kind#PALETTE}) or uncompressed: a list of fields of a little-endian texel word, each a channel of a type.
 * Decoded pictures are 8-bit, 16-bit or floating-point channel pictures of gray (one color field), RGB (two or
 * three) or RGBA layout; a missing green or blue is zero, a missing alpha opaque.
 *
 * <p>YUV texels convert to RGB as video does, with limited-range BT.601 coefficients: those of 8-bit samples
 * (Microsoft's "Recommended 8-Bit YUV Formats"), of 10-bit samples, and of 16-bit samples (Microsoft's "10-bit and
 * 16-bit YUV Video Formats"), in the integer arithmetic of DirectXTex. Subsampled chroma is replicated over the
 * texels it covers.
 */
public final class DdsFormat {
    /**
     * The coding of a format.
     */
    public enum Kind {
        BC1, BC2, BC3, BC4, BC5, BC6H, BC7, RGBG, GRGB, PLAIN,
        /**
         * One YUV texel per word, described by fields: channel 0 is Y, 1 is U (Cb), 2 is V (Cr), 3 is alpha.
         */
        YUV444,
        /**
         * Pairs of texels sharing their chroma: Y0, U, Y1, V samples, or U, Y0, V, Y1 for UYVY.
         */
        YUV422,
        /**
         * A Y plane followed by an interleaved U and V plane, or by a U plane and a V plane, whose samples each cover
         * a block of texels.
         */
        PLANAR,
        /**
         * One bit per texel, the most significant bit of each byte first, rows padded to bytes.
         */
        BITS,
        /**
         * 8-bit indices into a 256-entry palette of red, green, blue and alpha bytes, with or without an 8-bit alpha
         * after each index.
         */
        PALETTE
    }

    /**
     * The value type of a field.
     */
    public enum Type {
        UNORM, SNORM, UINT, SINT, FLOAT, SHARED_EXPONENT, BIASED
    }

    /**
     * One channel field of an uncompressed texel.
     *
     * @param channel 0 red, 1 green, 2 blue, 3 alpha; for {@link Kind#YUV444} 0 Y, 1 U, 2 V, 3 alpha
     * @param shift the bit offset in the texel word
     * @param bits the width in bits, from 1 to 32
     * @param type the value type
     */
    public record Field(int channel, int shift, int bits, Type type) {
    }

    private final String name;
    private final Kind kind;
    private final boolean signed;
    private final int bytesPerTexel;
    private final Field[] fields;
    private final boolean premultiplied;
    /**
     * The bytes of a YUV sample: 1, or 2 for little-endian 16-bit words.
     */
    private final int sampleBytes;
    /**
     * The right shift that aligns a YUV sample word to its significant bits (6 for the 10-bit samples of P010 and
     * Y210, whose low bits are unused).
     */
    private final int sampleShift;
    /**
     * The YUV conversion: 8, 10 or 16 bits.
     */
    private final int precision;
    /**
     * Whether a 4:2:2 pair is ordered U, Y0, V, Y1; for a planar format, whether the U and V samples are
     * interleaved in one plane; for a palette format, whether each index carries an alpha byte.
     */
    private final boolean alternate;
    /**
     * The texels a chroma sample of a planar format covers horizontally (1, 2 or 4) and vertically (1 or 2).
     */
    private final int chromaWidth;
    private final int chromaHeight;
    private final int[] palette;

    private DdsFormat(String name, Kind kind, boolean signed, int bytesPerTexel, Field[] fields,
                      boolean premultiplied, int sampleBytes, int sampleShift, int precision, boolean alternate,
                      int chromaWidth, int chromaHeight, int[] palette) {
        this.name = name;
        this.kind = kind;
        this.signed = signed;
        this.bytesPerTexel = bytesPerTexel;
        this.fields = fields;
        this.premultiplied = premultiplied;
        this.sampleBytes = sampleBytes;
        this.sampleShift = sampleShift;
        this.precision = precision;
        this.alternate = alternate;
        this.chromaWidth = chromaWidth;
        this.chromaHeight = chromaHeight;
        this.palette = palette;
    }

    /**
     * {@return a block-compressed, pair or 1-bit format}
     */
    public static DdsFormat of(String name, Kind kind, boolean signed) {
        return new DdsFormat(name, kind, signed, 0, new Field[0], false, 1, 0, 8, false, 1, 1, null);
    }

    /**
     * {@return an uncompressed format}
     */
    public static DdsFormat plain(String name, int bytesPerTexel, Field... fields) {
        return new DdsFormat(name, Kind.PLAIN, false, bytesPerTexel, fields, false, 1, 0, 8, false, 1, 1, null);
    }

    /**
     * Returns a 4:4:4 YUV format described by fields.
     *
     * <p>The conversion follows the width of the Y field: 8 and 10-bit samples convert with the coefficients of
     * their own depth, every other depth from 8 to 16 bits is aligned to 16 bits and converts with the 16-bit ones.
     *
     * @param name the name, for messages
     * @param bytesPerTexel the bytes of a texel word, at most 8
     * @param fields the Y, U and V fields, of one width from 8 to 16 bits, and an optional alpha field
     * @return the format
     * @throws MediaException.Unsupported if the Y, U and V fields differ in width or are narrower than 8 or wider than
     * 16 bits
     */
    public static DdsFormat yuv444(String name, int bytesPerTexel, Field... fields) {
        var width = -1;
        for (var field : fields) {
            if (field.channel() == 3) {
                continue;
            }
            if (width >= 0 && field.bits() != width) {
                throw new MediaException.Unsupported("dds", "YUV channel masks of different widths");
            }
            width = field.bits();
        }
        if (width < 8 || width > 16) {
            throw new MediaException.Unsupported("dds", width + "-bit YUV channel masks");
        }
        var precision = width == 8 || width == 10 ? width : 16;
        return new DdsFormat(name, Kind.YUV444, false, bytesPerTexel, fields, false, 1, 0, precision, false, 1, 1,
                null);
    }

    /**
     * Returns a packed 4:2:2 YUV format.
     *
     * @param name the name, for messages
     * @param sampleBytes 1 for 8-bit samples, 2 for 16-bit words
     * @param sampleShift the right shift to the significant bits of a sample word
     * @param precision the conversion: 8, 10 or 16 bits
     * @param uyvy whether a pair is ordered U, Y0, V, Y1 rather than Y0, U, Y1, V
     * @return the format
     */
    public static DdsFormat yuv422(String name, int sampleBytes, int sampleShift, int precision, boolean uyvy) {
        return new DdsFormat(name, Kind.YUV422, false, 0, new Field[0], false, sampleBytes, sampleShift, precision,
                uyvy, 2, 1, null);
    }

    /**
     * Returns a planar YUV format.
     *
     * <p>Its row pitch is the width rounded up to a multiple of {@code chromaWidth}, in samples; the Y plane has a
     * row per texel row, and each chroma plane, or the one interleaved plane, a row per {@code chromaHeight} texel
     * rows, rounded up. The rows of an interleaved plane are as long as its samples, which leaves the end of the
     * surface unused when they are shorter than the pitch (NV11).
     *
     * @param name the name, for messages
     * @param sampleBytes 1 for 8-bit samples, 2 for 16-bit words
     * @param sampleShift the right shift to the significant bits of a sample word
     * @param precision the conversion: 8, 10 or 16 bits
     * @param chromaWidth the texels a chroma sample covers horizontally: 1, 2 or 4
     * @param chromaHeight the texels a chroma sample covers vertically: 1 or 2
     * @param interleaved whether U and V alternate in one plane rather than fill a plane each
     * @return the format
     */
    public static DdsFormat planar(String name, int sampleBytes, int sampleShift, int precision, int chromaWidth,
                                   int chromaHeight, boolean interleaved) {
        return new DdsFormat(name, Kind.PLANAR, false, 0, new Field[0], false, sampleBytes, sampleShift, precision,
                interleaved, chromaWidth, chromaHeight, null);
    }

    /**
     * Returns a legacy palette format.
     *
     * @param name the name, for messages
     * @param palette the 256 entries, each red in its low byte, then green, blue and alpha
     * @param alphaBytes whether each index is followed by an alpha byte, which replaces the palette's alpha
     * @return the format
     */
    public static DdsFormat palette(String name, int[] palette, boolean alphaBytes) {
        return new DdsFormat(name, Kind.PALETTE, false, alphaBytes ? 2 : 1, new Field[0], false, 1, 0, 8, alphaBytes,
                1, 1, palette);
    }

    /**
     * {@return this format with premultiplied alpha, divided out when the texels decode}
     */
    public DdsFormat premultiplied() {
        return new DdsFormat(name, kind, signed, bytesPerTexel, fields, true, sampleBytes, sampleShift, precision,
                alternate, chromaWidth, chromaHeight, palette);
    }

    /**
     * {@return the name of the format, for messages}
     */
    public String name() {
        return name;
    }

    /**
     * {@return the coding}
     */
    public Kind kind() {
        return kind;
    }

    /**
     * {@return whether the texels carry alpha}
     */
    public boolean hasAlpha() {
        return switch (kind) {
            case BC1, BC2, BC3, BC7 -> true;
            case PALETTE -> alternate || translucent(palette);
            case PLAIN, YUV444 -> fieldOf(3) != null;
            default -> false;
        };
    }

    /**
     * {@return the bits per sample of the decoded picture: 8, 16 or 32 for floating point}
     */
    public int bitDepth() {
        return switch (kind) {
            case BC6H -> 32;
            case YUV444, YUV422, PLANAR -> precision == 8 ? 8 : 16;
            case PLAIN -> {
                var depth = 8;
                for (var field : fields) {
                    if (field.type() == Type.FLOAT || field.type() == Type.SHARED_EXPONENT
                            || field.type() == Type.BIASED || field.bits() > 16) {
                        yield 32;
                    }
                    if (field.bits() > 8) {
                        depth = 16;
                    }
                }
                yield depth;
            }
            default -> 8;
        };
    }

    /**
     * Returns the byte size of a surface.
     *
     * @param width the width, positive
     * @param height the height, positive
     * @return the size in bytes
     */
    public long surfaceBytes(int width, int height) {
        return switch (kind) {
            case BC1, BC4 -> ((width + 3L) / 4) * ((height + 3L) / 4) * 8;
            case BC2, BC3, BC5, BC6H, BC7 -> ((width + 3L) / 4) * ((height + 3L) / 4) * 16;
            case RGBG, GRGB -> ((width + 1L) / 2) * 4 * height;
            case PLAIN, YUV444, PALETTE -> (long) width * height * bytesPerTexel;
            case YUV422 -> ((width + 1L) / 2) * 4 * sampleBytes * height;
            case PLANAR -> planarPitch(width) * (height + (alternate ? 1 : 2) * chromaRows(height));
            case BITS -> ((width + 7L) / 8) * height;
        };
    }

    /**
     * {@return the bytes of a row of the Y plane of a planar format, and of a chroma plane that is not interleaved}
     */
    private long planarPitch(int width) {
        return ((width + chromaWidth - 1L) / chromaWidth) * chromaWidth * sampleBytes;
    }

    private long chromaRows(int height) {
        return (height + chromaHeight - 1L) / chromaHeight;
    }

    /**
     * {@return whether an entry of a palette is not opaque}
     */
    private static boolean translucent(int[] palette) {
        for (var entry : palette) {
            if (entry >>> 24 != 0xFF) {
                return true;
            }
        }
        return false;
    }

    private Field fieldOf(int channel) {
        for (var field : fields) {
            if (field.channel() == channel) {
                return field;
            }
        }
        return null;
    }

    /**
     * {@return the layout of the decoded picture}
     */
    private PixelLayout layout() {
        return switch (kind) {
            case BC4, BITS -> PixelLayout.GRAY;
            case BC5, BC6H, RGBG, GRGB, YUV422, PLANAR -> PixelLayout.RGB;
            case BC1, BC2, BC3, BC7, PALETTE -> PixelLayout.RGBA;
            case YUV444 -> fieldOf(3) != null ? PixelLayout.RGBA : PixelLayout.RGB;
            case PLAIN -> {
                var colors = 0;
                for (var field : fields) {
                    if (field.channel() < 3) {
                        colors = Math.max(colors, field.channel() + 1);
                    }
                }
                var alpha = fieldOf(3) != null;
                if (fields.length == 1 && fields[0].type() == Type.SHARED_EXPONENT) {
                    yield PixelLayout.RGB;
                }
                if (colors == 1 && !alpha) {
                    yield PixelLayout.GRAY;
                }
                if (colors == 1) {
                    yield PixelLayout.GRAY_ALPHA;
                }
                yield alpha ? PixelLayout.RGBA : PixelLayout.RGB;
            }
        };
    }

    /**
     * Decodes one surface.
     *
     * @param data the file
     * @param offset the offset of the surface
     * @param width the width, positive
     * @param height the height, positive
     * @return the picture
     * @throws MediaException.Malformed if the surface runs past the end of the file
     */
    public Picture decode(byte[] data, long offset, int width, int height) {
        var size = surfaceBytes(width, height);
        if (offset + size > data.length) {
            throw new MediaException.Malformed("the DDS surface of " + size + " bytes at offset " + offset
                    + " runs past the end of the file");
        }
        var start = (int) offset;
        return switch (kind) {
            case BC1, BC2, BC3, BC7 -> rgbaBlocks(data, start, width, height);
            case BC4, BC5 -> channelBlocks(data, start, width, height);
            case BC6H -> halfBlocks(data, start, width, height);
            case RGBG, GRGB -> pairs(data, start, width, height);
            case PLAIN -> texels(data, start, width, height);
            case YUV444, YUV422, PLANAR -> yuv(data, start, width, height);
            case BITS -> bits(data, start, width, height);
            case PALETTE -> indexed(data, start, width, height);
        };
    }

    private Picture rgbaBlocks(byte[] data, int start, int width, int height) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.RGBA);
        var pixels = picture.pixels();
        var texels = new int[16];
        var blocks = new BcnBlocks();
        var alpha = new int[16];
        var blockBytes = kind == Kind.BC1 ? 8 : 16;
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        for (var by = 0; by < rows; by++) {
            for (var bx = 0; bx < columns; bx++) {
                var at = start + (by * columns + bx) * blockBytes;
                switch (kind) {
                    case BC1 -> blocks.color(data, at, texels, false);
                    case BC2 -> {
                        blocks.color(data, at + 8, texels, true);
                        blocks.sharpAlpha(data, at, texels);
                    }
                    case BC3 -> {
                        blocks.color(data, at + 8, texels, true);
                        blocks.smooth(data, at, alpha, false, false);
                        for (var texel = 0; texel < 16; texel++) {
                            texels[texel] = (texels[texel] & 0x00FFFFFF) | alpha[texel] << 24;
                        }
                    }
                    default -> blocks.bc7(data, at, texels);
                }
                for (var texel = 0; texel < 16; texel++) {
                    var x = bx * 4 + (texel & 3);
                    var y = by * 4 + (texel >>> 2);
                    if (x >= width || y >= height) {
                        continue;
                    }
                    var value = texels[texel];
                    var out = (y * width + x) * 4;
                    var a = value >>> 24;
                    var r = value & 0xFF;
                    var g = (value >>> 8) & 0xFF;
                    var b = (value >>> 16) & 0xFF;
                    if (premultiplied) {
                        r = unpremultiply(r, a, 255);
                        g = unpremultiply(g, a, 255);
                        b = unpremultiply(b, a, 255);
                    }
                    pixels[out] = (byte) r;
                    pixels[out + 1] = (byte) g;
                    pixels[out + 2] = (byte) b;
                    pixels[out + 3] = (byte) a;
                }
            }
        }
        return picture;
    }

    private Picture channelBlocks(byte[] data, int start, int width, int height) {
        var channels = kind == Kind.BC4 ? 1 : 3;
        var picture = Channel8Picture.allocate(width, height, layout());
        var pixels = picture.pixels();
        var values = new int[16];
        var blocks = new BcnBlocks();
        var blockBytes = kind == Kind.BC4 ? 8 : 16;
        var swapped = name.equals("ATI2");
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        for (var by = 0; by < rows; by++) {
            for (var bx = 0; bx < columns; bx++) {
                var at = start + (by * columns + bx) * blockBytes;
                for (var channel = 0; channel < (kind == Kind.BC4 ? 1 : 2); channel++) {
                    blocks.smooth(data, at + channel * 8, values, signed, true);
                    for (var texel = 0; texel < 16; texel++) {
                        var x = bx * 4 + (texel & 3);
                        var y = by * 4 + (texel >>> 2);
                        if (x < width && y < height) {
                            var value = values[texel];
                            pixels[(y * width + x) * channels + (swapped ? 1 - channel : channel)] = (byte) (signed
                                    ? ((value + 127) * 255 + 127) / 254 : value);
                        }
                    }
                }
            }
        }
        return picture;
    }

    private Picture halfBlocks(byte[] data, int start, int width, int height) {
        var picture = ChannelFloatPicture.allocate(width, height, PixelLayout.RGB);
        var pixels = picture.pixels();
        var halves = new short[48];
        var blocks = new BcnBlocks();
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        for (var by = 0; by < rows; by++) {
            for (var bx = 0; bx < columns; bx++) {
                blocks.bc6h(data, start + (by * columns + bx) * 16, halves, signed);
                for (var texel = 0; texel < 16; texel++) {
                    var x = bx * 4 + (texel & 3);
                    var y = by * 4 + (texel >>> 2);
                    if (x < width && y < height) {
                        var out = (y * width + x) * 3;
                        for (var channel = 0; channel < 3; channel++) {
                            pixels[out + channel] = Float.float16ToFloat(halves[3 * texel + channel]);
                        }
                    }
                }
            }
        }
        return picture;
    }

    private Picture pairs(byte[] data, int start, int width, int height) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.RGB);
        var pixels = picture.pixels();
        var rowBytes = ((width + 1) / 2) * 4;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var pair = start + y * rowBytes + (x / 2) * 4;
                int r;
                int g;
                int b;
                if (kind == Kind.RGBG) {
                    r = data[pair] & 0xFF;
                    g = data[pair + 1 + 2 * (x & 1)] & 0xFF;
                    b = data[pair + 2] & 0xFF;
                } else {
                    g = data[pair + 2 * (x & 1)] & 0xFF;
                    r = data[pair + 1] & 0xFF;
                    b = data[pair + 3] & 0xFF;
                }
                var out = (y * width + x) * 3;
                pixels[out] = (byte) r;
                pixels[out + 1] = (byte) g;
                pixels[out + 2] = (byte) b;
            }
        }
        return picture;
    }

    private Picture bits(byte[] data, int start, int width, int height) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.GRAY);
        var pixels = picture.pixels();
        var rowBytes = (width + 7) / 8;
        for (var y = 0; y < height; y++) {
            var row = start + y * rowBytes;
            for (var x = 0; x < width; x++) {
                pixels[y * width + x] = (byte) (((data[row + (x >>> 3)] >>> (7 - (x & 7))) & 1) * 255);
            }
        }
        return picture;
    }

    private Picture indexed(byte[] data, int start, int width, int height) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.RGBA);
        var pixels = picture.pixels();
        var count = width * height;
        for (var index = 0; index < count; index++) {
            var at = start + index * bytesPerTexel;
            var entry = palette[data[at] & 0xFF];
            var out = index * 4;
            pixels[out] = (byte) entry;
            pixels[out + 1] = (byte) (entry >>> 8);
            pixels[out + 2] = (byte) (entry >>> 16);
            pixels[out + 3] = alternate ? data[at + 1] : (byte) (entry >>> 24);
        }
        return picture;
    }

    /**
     * Decodes a YUV surface to 8-bit RGB(A) for 8-bit precision and to 16-bit RGB(A) otherwise, dividing out
     * premultiplied alpha.
     */
    private Picture yuv(byte[] data, int start, int width, int height) {
        var layout = layout();
        var channels = layout.count();
        var count = width * height;
        var narrow = precision == 8 ? new byte[count * channels] : null;
        var wide = precision == 8 ? null : new short[count * channels];
        var alpha = fieldOf(3);
        var y = fieldOf(0);
        var u = fieldOf(1);
        var v = fieldOf(2);
        var pitch = kind == Kind.PLANAR ? (int) planarPitch(width) : 0;
        var unpremultiply = premultiplied && channels == 4;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                int ys;
                int us;
                int vs;
                var a = -1L;
                switch (kind) {
                    case YUV444 -> {
                        var at = start + (row * width + column) * bytesPerTexel;
                        var word = word(data, at, bytesPerTexel);
                        var align = precision == 16 ? 16 - y.bits() : 0;
                        ys = (int) bitsOf(word, y) << align;
                        us = (int) bitsOf(word, u) << align;
                        vs = (int) bitsOf(word, v) << align;
                        if (alpha != null) {
                            a = bitsOf(word, alpha);
                        }
                    }
                    case YUV422 -> {
                        var pair = start + (row * ((width + 1) / 2) + column / 2) * 4 * sampleBytes;
                        var lead = alternate ? 1 : 0;
                        ys = sample(data, pair + (lead + 2 * (column & 1)) * sampleBytes);
                        us = sample(data, pair + (1 - lead) * sampleBytes);
                        vs = sample(data, pair + (3 - lead) * sampleBytes);
                    }
                    default -> {
                        ys = sample(data, start + row * pitch + column * sampleBytes);
                        var chromaRow = row / chromaHeight;
                        var chromaColumn = column / chromaWidth;
                        var plane = start + height * pitch;
                        if (alternate) {
                            var rowBytes = ((width + chromaWidth - 1) / chromaWidth) * 2 * sampleBytes;
                            var at = plane + chromaRow * rowBytes + chromaColumn * 2 * sampleBytes;
                            us = sample(data, at);
                            vs = sample(data, at + sampleBytes);
                        } else {
                            var at = plane + chromaRow * pitch + chromaColumn * sampleBytes;
                            us = sample(data, at);
                            vs = sample(data, at + (int) chromaRows(height) * pitch);
                        }
                    }
                }
                var out = (row * width + column) * channels;
                if (narrow != null) {
                    var luma = 298 * (ys - 16);
                    var cb = us - 128;
                    var cr = vs - 128;
                    narrow[out] = (byte) Math.clamp((luma + 409 * cr + 128) >> 8, 0, 255);
                    narrow[out + 1] = (byte) Math.clamp((luma - 100 * cb - 208 * cr + 128) >> 8, 0, 255);
                    narrow[out + 2] = (byte) Math.clamp((luma + 516 * cb + 128) >> 8, 0, 255);
                    if (channels == 4) {
                        var opacity = (int) Math.round(a * 255.0 / ((1L << alpha.bits()) - 1));
                        narrow[out + 3] = (byte) opacity;
                        if (unpremultiply) {
                            for (var channel = 0; channel < 3; channel++) {
                                narrow[out + channel] = (byte) unpremultiply(narrow[out + channel] & 0xFF, opacity,
                                        255);
                            }
                        }
                    }
                } else if (precision == 10) {
                    var luma = 76533L * (ys - 64);
                    long cb = us - 512;
                    long cr = vs - 512;
                    wide[out] = widen(Math.clamp((luma + 104905 * cr + 32768) >> 16, 0, 1023), 1023);
                    wide[out + 1] = widen(Math.clamp((luma - 25747 * cb - 53425 * cr + 32768) >> 16, 0, 1023), 1023);
                    wide[out + 2] = widen(Math.clamp((luma + 132590 * cb + 32768) >> 16, 0, 1023), 1023);
                    if (channels == 4) {
                        wide[out + 3] = widen(a, (1L << alpha.bits()) - 1);
                    }
                } else {
                    var luma = 76607L * (ys - 4096);
                    long cb = us - 32768;
                    long cr = vs - 32768;
                    wide[out] = (short) Math.clamp((luma + 105006 * cr + 32768) >> 16, 0, 65535);
                    wide[out + 1] = (short) Math.clamp((luma - 25772 * cb - 53477 * cr + 32768) >> 16, 0, 65535);
                    wide[out + 2] = (short) Math.clamp((luma + 132718 * cb + 32768) >> 16, 0, 65535);
                    if (channels == 4) {
                        wide[out + 3] = widen(a, (1L << alpha.bits()) - 1);
                    }
                }
                if (unpremultiply && wide != null) {
                    var opacity = wide[out + 3] & 0xFFFF;
                    for (var channel = 0; channel < 3; channel++) {
                        wide[out + channel] = (short) unpremultiply(wide[out + channel] & 0xFFFF, opacity, 65535);
                    }
                }
            }
        }
        if (narrow != null) {
            return Channel8Picture.of(width, height, width * channels, narrow, layout);
        }
        return Channel16Picture.of(width, height, width * channels, wide, layout);
    }

    /**
     * Reads one YUV sample: a byte, or the significant bits of a little-endian 16-bit word.
     */
    private int sample(byte[] data, int at) {
        if (sampleBytes == 1) {
            return data[at] & 0xFF;
        }
        return ((data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8) >>> sampleShift;
    }

    /**
     * Scales a value of {@code 0..max} to {@code 0..65535}, rounding to nearest.
     */
    private static short widen(long value, long max) {
        return (short) ((value * 65535 + max / 2) / max);
    }

    private static long bitsOf(long word, Field field) {
        return (word >>> field.shift()) & ((1L << field.bits()) - 1);
    }

    private Picture texels(byte[] data, int start, int width, int height) {
        var layout = layout();
        var channels = layout.count();
        var depth = bitDepth();
        var gray = layout.colorChannels() == 1;
        var count = width * height;
        float[] floats = depth == 32 ? new float[count * channels] : null;
        short[] wide = depth == 16 ? new short[count * channels] : null;
        byte[] narrow = depth == 8 ? new byte[count * channels] : null;
        var values = new double[4];
        for (var index = 0; index < count; index++) {
            var at = start + index * bytesPerTexel;
            values[0] = 0;
            values[1] = 0;
            values[2] = 0;
            values[3] = 1;
            if (fields.length == 1 && fields[0].type() == Type.SHARED_EXPONENT) {
                sharedExponent(word(data, at, 4), values);
            } else {
                for (var field : fields) {
                    values[field.channel()] = value(data, at, field);
                }
            }
            if (premultiplied && values[3] > 0) {
                for (var channel = 0; channel < 3; channel++) {
                    values[channel] = Math.min(depth == 32 ? Double.MAX_VALUE : 1.0, values[channel] / values[3]);
                }
            } else if (premultiplied) {
                values[0] = values[1] = values[2] = 0;
            }
            var base = index * channels;
            for (var channel = 0; channel < channels; channel++) {
                var source = gray ? (channel == 0 ? 0 : 3) : channel;
                var value = values[source];
                if (floats != null) {
                    floats[base + channel] = (float) value;
                } else if (wide != null) {
                    wide[base + channel] = (short) Math.round(Math.clamp(value, 0, 1) * 65535);
                } else {
                    narrow[base + channel] = (byte) Math.round(Math.clamp(value, 0, 1) * 255);
                }
            }
        }
        if (floats != null) {
            return ChannelFloatPicture.of(width, height, width * channels, floats, layout);
        }
        if (wide != null) {
            return Channel16Picture.of(width, height, width * channels, wide, layout);
        }
        return Channel8Picture.of(width, height, width * channels, narrow, layout);
    }

    /**
     * Reads a little-endian word of up to eight bytes.
     */
    private static long word(byte[] data, int offset, int bytes) {
        var value = 0L;
        for (var index = bytes - 1; index >= 0; index--) {
            value = value << 8 | (data[offset + index] & 0xFF);
        }
        return value;
    }

    /**
     * Decodes one field to its nominal value: unsigned normalized and unsigned integer fields to 0..1, signed ones
     * from -1..1 to 0..1 as {@code (v + 1) / 2}, floating-point fields as they are.
     */
    private double value(byte[] data, int at, Field field) {
        var byteOffset = field.shift() / 8;
        var bitOffset = field.shift() % 8;
        var span = (bitOffset + field.bits() + 7) / 8;
        var raw = (word(data, at + byteOffset, span) >>> bitOffset) & ((1L << field.bits()) - 1);
        var max = (double) ((1L << field.bits()) - 1);
        return switch (field.type()) {
            case UNORM, UINT -> raw / max;
            case SNORM, SINT -> {
                var half = 1L << (field.bits() - 1);
                var signedValue = raw >= half ? raw - (1L << field.bits()) : raw;
                var normalized = Math.max(-1.0, signedValue / (double) (half - 1));
                yield (normalized + 1) / 2;
            }
            case BIASED -> (raw - 0x180) / 510.0;
            case FLOAT -> switch (field.bits()) {
                case 32 -> Float.intBitsToFloat((int) raw);
                case 16 -> Float.float16ToFloat((short) raw);
                case 11 -> smallFloat(raw, 6);
                case 10 -> smallFloat(raw, 5);
                default -> throw new MediaException.Unsupported("dds", field.bits() + "-bit floats");
            };
            case SHARED_EXPONENT -> throw new IllegalStateException("shared exponents decode per texel");
        };
    }

    /**
     * Decodes an unsigned 11-bit or 10-bit float of a 5-bit exponent and a 6 or 5-bit mantissa.
     */
    private static double smallFloat(long raw, int mantissaBits) {
        var exponent = (int) (raw >>> mantissaBits) & 31;
        var mantissa = (int) raw & ((1 << mantissaBits) - 1);
        if (exponent == 31) {
            return mantissa == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        }
        if (exponent == 0) {
            return Math.scalb((double) mantissa, -14 - mantissaBits);
        }
        return Math.scalb(1.0 + mantissa / (double) (1 << mantissaBits), exponent - 15);
    }

    /**
     * Decodes a {@code R9G9B9E5_SHAREDEXP} texel.
     */
    private static void sharedExponent(long word, double[] values) {
        var exponent = (int) (word >>> 27) & 31;
        var scale = Math.scalb(1.0, exponent - 15 - 9);
        values[0] = (word & 0x1FF) * scale;
        values[1] = ((word >>> 9) & 0x1FF) * scale;
        values[2] = ((word >>> 18) & 0x1FF) * scale;
    }

    /**
     * Divides premultiplied alpha out of a color value of {@code 0..max}, rounding to nearest; a transparent texel
     * is black.
     */
    private static int unpremultiply(int value, int alpha, int max) {
        return alpha == 0 ? 0 : (int) Math.min(max, ((long) value * max + alpha / 2) / alpha);
    }
}
