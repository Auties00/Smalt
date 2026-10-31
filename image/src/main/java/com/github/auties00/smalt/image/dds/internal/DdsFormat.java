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
 * ({@link Kind#RGBG}, {@link Kind#GRGB}) or uncompressed: a list of fields of a little-endian texel word, each a
 * channel of a type. Decoded pictures are 8-bit, 16-bit or floating-point channel pictures of gray (one color field),
 * RGB (two or three) or RGBA layout; a missing green or blue is zero, a missing alpha opaque.
 */
public final class DdsFormat {
    /**
     * The coding of a format.
     */
    public enum Kind {
        BC1, BC2, BC3, BC4, BC5, BC6H, BC7, RGBG, GRGB, PLAIN
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
     * @param channel 0 red, 1 green, 2 blue, 3 alpha
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

    private DdsFormat(String name, Kind kind, boolean signed, int bytesPerTexel, Field[] fields,
                      boolean premultiplied) {
        this.name = name;
        this.kind = kind;
        this.signed = signed;
        this.bytesPerTexel = bytesPerTexel;
        this.fields = fields;
        this.premultiplied = premultiplied;
    }

    /**
     * {@return a block-compressed or pair format}
     */
    public static DdsFormat of(String name, Kind kind, boolean signed) {
        return new DdsFormat(name, kind, signed, 0, new Field[0], false);
    }

    /**
     * {@return an uncompressed format}
     */
    public static DdsFormat plain(String name, int bytesPerTexel, Field... fields) {
        return new DdsFormat(name, Kind.PLAIN, false, bytesPerTexel, fields, false);
    }

    /**
     * {@return this format with premultiplied alpha, divided out when the texels decode}
     */
    public DdsFormat premultiplied() {
        return new DdsFormat(name, kind, signed, bytesPerTexel, fields, true);
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
            case PLAIN -> fieldOf(3) != null;
            default -> false;
        };
    }

    /**
     * {@return the bits per sample of the decoded picture: 8, 16 or 32 for floating point}
     */
    public int bitDepth() {
        return switch (kind) {
            case BC6H -> 32;
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
            case PLAIN -> (long) width * height * bytesPerTexel;
        };
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
            case BC4 -> PixelLayout.GRAY;
            case BC5, BC6H, RGBG, GRGB -> PixelLayout.RGB;
            case BC1, BC2, BC3, BC7 -> PixelLayout.RGBA;
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

    private static int unpremultiply(int value, int alpha, int max) {
        return alpha == 0 ? 0 : Math.min(max, (value * max + alpha / 2) / alpha);
    }
}
