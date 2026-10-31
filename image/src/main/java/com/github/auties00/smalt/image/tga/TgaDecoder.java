package com.github.auties00.smalt.image.tga;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;

/**
 * Decodes Truevision TGA (Targa) images to packed RGBA.
 *
 * <p>Supported: color-mapped (types 1 and 9), true-color (types 2 and 10) and gray (types 3 and 11) images, raw and
 * run-length coded, run-length packets crossing scanlines; 8 and 16-bit color map indices, 15, 16, 24 and 32-bit
 * color map entries; 15, 16, 24 and 32-bit true-color pixels; 8-bit gray and 16-bit gray with an 8-bit attribute;
 * every origin of the image descriptor (bottom-left, the default, top-left, and the right-to-left ones), the pixels
 * being returned top row first and left to right; and the TGA 2.0 extension area's attributes type. The obsolete
 * Huffman-coded types (32 and 33) are unsupported.
 *
 * <p>Five-bit channels expand to eight by bit replication, {@code v << 3 | v >> 2}. The alpha channel is the
 * descriptor's attribute bits (the top bit of a 16-bit pixel, the fourth byte of a 32-bit one, the second byte of a
 * 16-bit gray pixel); an image whose descriptor declares no attribute bits is opaque. When the file has a TGA 2.0
 * extension area, its attributes type decides: 3 is alpha, 4 premultiplied alpha (divided out, as
 * {@code min(255, (v * 255 + a / 2) / a)}, 0 when {@code a} is 0), and 0, 1 and 2 mean the attribute bits are not
 * alpha, so the image is opaque. A color map index outside the map paints opaque black. A pixel stream that ends
 * early leaves the remaining pixels transparent black.
 *
 * <p>This class is thread-safe.
 */
public final class TgaDecoder {
    private static final String WHAT = "TGA image";

    /**
     * The signature of the TGA 2.0 footer, NUL included.
     */
    private static final byte[] SIGNATURE = "TRUEVISION-XFILE.\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private TgaDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a TGA image from its header.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} when the image decodes with an alpha channel.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the image type is unsupported
     * @throws MediaException.LimitExceeded if the file exceeds the default byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var header = Header.parse(data);
                return new ImageInfo(ImageFormat.TGA, header.width, header.height, header.alpha != Alpha.NONE, false,
                        1, Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes a TGA image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the image type is unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a TGA image.
     *
     * <p>The size hint, the bit depth and the channel choices of {@code options} are ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region and the sRGB conversion choice
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header is invalid
     * @throws MediaException.Unsupported if the image type is unsupported
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                return decode(data, whole);
            }));
        }
    }

    /**
     * How the attribute bits are used.
     */
    private enum Alpha {
        NONE,
        STRAIGHT,
        PREMULTIPLIED
    }

    /**
     * The fields of a TGA header and where its parts lie.
     */
    private record Header(int idLength, int mapType, int imageType, int mapFirst, int mapLength, int mapBits,
                          int width, int height, int depth, int descriptor, Alpha alpha, int mapOffset,
                          int dataOffset) {
        static Header parse(byte[] data) {
            if (data.length < 18) {
                throw new MediaException.Malformed("the TGA header is truncated");
            }
            var idLength = data[0] & 0xFF;
            var mapType = data[1] & 0xFF;
            var imageType = data[2] & 0xFF;
            var mapFirst = ByteArrays.u16le(data, 3);
            var mapLength = ByteArrays.u16le(data, 5);
            var mapBits = data[7] & 0xFF;
            var width = ByteArrays.u16le(data, 12);
            var height = ByteArrays.u16le(data, 14);
            var depth = data[16] & 0xFF;
            var descriptor = data[17] & 0xFF;
            if (imageType == 32 || imageType == 33) {
                throw new MediaException.Unsupported("tga", "Huffman-coded TGA images (type " + imageType + ")");
            }
            if (width == 0 || height == 0) {
                throw new MediaException.Malformed("the TGA header declares no picture size");
            }
            var base = imageType & ~8;
            if (imageType == 0 || base < 1 || base > 3 || (imageType & ~11) != 0) {
                throw new MediaException.Unsupported("tga", "TGA image type " + imageType);
            }
            if (mapType > 1) {
                throw new MediaException.Malformed("invalid TGA color map type " + mapType);
            }
            if (base == 1 && (mapType != 1 || (depth != 8 && depth != 16))) {
                throw new MediaException.Malformed("a color-mapped TGA image needs a color map and 8 or 16-bit"
                        + " indices");
            }
            if (base == 2 && depth != 15 && depth != 16 && depth != 24 && depth != 32) {
                throw new MediaException.Malformed("invalid TGA true-color depth " + depth);
            }
            if (base == 3 && depth != 8 && depth != 16) {
                throw new MediaException.Malformed("invalid TGA gray depth " + depth);
            }
            if (mapType == 1 && mapBits != 15 && mapBits != 16 && mapBits != 24 && mapBits != 32) {
                throw new MediaException.Malformed("invalid TGA color map entry size " + mapBits);
            }
            var mapOffset = 18 + idLength;
            var mapBytes = mapType == 1 ? mapLength * ((mapBits + 7) / 8) : 0;
            var dataOffset = mapOffset + mapBytes;
            if (dataOffset > data.length) {
                throw new MediaException.Malformed("the TGA color map is truncated");
            }
            var bits = descriptor & 0x0F;
            var carriesAlpha = switch (base) {
                case 1 -> mapBits == 32 || mapBits == 16;
                case 2 -> depth == 32 || depth == 16;
                default -> depth == 16;
            };
            var alpha = bits > 0 && carriesAlpha ? Alpha.STRAIGHT : Alpha.NONE;
            if (alpha != Alpha.NONE) {
                var type = attributesType(data);
                if (type == 4) {
                    alpha = Alpha.PREMULTIPLIED;
                } else if (type >= 0 && type != 3) {
                    alpha = Alpha.NONE;
                }
            }
            return new Header(idLength, mapType, imageType, mapFirst, mapLength, mapBits, width, height, depth,
                    descriptor, alpha, mapOffset, dataOffset);
        }
    }

    /**
     * Returns the attributes type of the TGA 2.0 extension area, or {@code -1} when the file has none.
     */
    private static int attributesType(byte[] data) {
        if (data.length < 26 + 18) {
            return -1;
        }
        var footer = data.length - 26;
        for (var index = 0; index < SIGNATURE.length; index++) {
            if (data[footer + 8 + index] != SIGNATURE[index]) {
                return -1;
            }
        }
        var extension = ByteArrays.u32le(data, footer);
        if (extension == 0 || extension + 495 > footer || extension < 18) {
            return -1;
        }
        if (ByteArrays.u16le(data, (int) extension) < 495) {
            return -1;
        }
        return data[(int) extension + 494] & 0xFF;
    }

    /**
     * Decodes a whole file held in memory.
     */
    private static DecodedImage decode(byte[] data, ImageDecoderOptions options) {
        var header = Header.parse(data);
        var width = header.width;
        var height = header.height;
        LimitChecks.checkPicture(options.limits(), width, height);
        LimitChecks.checkAllocation(options.limits(), (long) width * height * 4);
        var palette = palette(data, header);
        var base = header.imageType & ~8;
        var bytes = (header.depth + 7) / 8;
        var count = width * height;
        var decoded = new int[count];
        var position = header.dataOffset;
        var filled = 0;
        if ((header.imageType & 8) == 0) {
            var available = (int) Math.min(count, (data.length - position) / bytes);
            for (; filled < available; filled++) {
                decoded[filled] = pixel(data, position, base, header, palette);
                position += bytes;
            }
        } else {
            while (filled < count && position < data.length) {
                var packet = data[position++] & 0xFF;
                var run = Math.min((packet & 0x7F) + 1, count - filled);
                if ((packet & 0x80) != 0) {
                    if (position + bytes > data.length) {
                        break;
                    }
                    var value = pixel(data, position, base, header, palette);
                    position += bytes;
                    java.util.Arrays.fill(decoded, filled, filled + run, value);
                    filled += run;
                } else {
                    for (var index = 0; index < run && position + bytes <= data.length; index++) {
                        decoded[filled++] = pixel(data, position, base, header, palette);
                        position += bytes;
                    }
                    if (position + bytes > data.length && filled < count) {
                        break;
                    }
                }
            }
        }
        var transparentTail = filled < count;
        var alpha = header.alpha != Alpha.NONE || transparentTail;
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        var rightToLeft = (header.descriptor & 0x10) != 0;
        var topDown = (header.descriptor & 0x20) != 0;
        for (var index = 0; index < filled; index++) {
            var row = index / width;
            var column = index % width;
            var y = topDown ? row : height - 1 - row;
            var x = rightToLeft ? width - 1 - column : column;
            var argb = decoded[index];
            var at = (y * width + x) * 4;
            var a = header.alpha == Alpha.NONE ? 255 : argb >>> 24;
            var r = (argb >>> 16) & 0xFF;
            var g = (argb >>> 8) & 0xFF;
            var b = argb & 0xFF;
            if (header.alpha == Alpha.PREMULTIPLIED) {
                r = unpremultiply(r, a);
                g = unpremultiply(g, a);
                b = unpremultiply(b, a);
            }
            pixels[at] = (byte) r;
            pixels[at + 1] = (byte) g;
            pixels[at + 2] = (byte) b;
            pixels[at + 3] = (byte) a;
        }
        return RasterOutput.finish(picture, ImageFormat.TGA, Orientation.NORMAL, null, null, options);
    }

    /**
     * Divides a premultiplied channel by its alpha.
     */
    private static int unpremultiply(int value, int alpha) {
        return alpha == 0 ? 0 : Math.min(255, (value * 255 + alpha / 2) / alpha);
    }

    /**
     * Reads the color map as ARGB entries.
     */
    private static int[] palette(byte[] data, Header header) {
        if (header.mapType != 1 || (header.imageType & ~8) != 1) {
            return null;
        }
        var bytes = (header.mapBits + 7) / 8;
        var entries = new int[header.mapLength];
        for (var index = 0; index < entries.length; index++) {
            entries[index] = color(data, header.mapOffset + index * bytes, header.mapBits);
        }
        return entries;
    }

    /**
     * Reads one pixel as ARGB.
     */
    private static int pixel(byte[] data, int position, int base, Header header, int[] palette) {
        return switch (base) {
            case 1 -> {
                var index = header.depth == 8 ? data[position] & 0xFF : ByteArrays.u16le(data, position);
                var entry = index - header.mapFirst;
                yield entry >= 0 && entry < palette.length ? palette[entry] : 0xFF000000;
            }
            case 2 -> color(data, position, header.depth);
            default -> {
                var gray = data[position] & 0xFF;
                var alpha = header.depth == 16 ? data[position + 1] & 0xFF : 255;
                yield alpha << 24 | gray << 16 | gray << 8 | gray;
            }
        };
    }

    /**
     * Reads a 15, 16, 24 or 32-bit color as ARGB, a 16-bit color's top bit and a 32-bit color's fourth byte
     * giving alpha.
     */
    private static int color(byte[] data, int position, int bits) {
        return switch (bits) {
            case 15, 16 -> {
                var value = ByteArrays.u16le(data, position);
                var r = expand((value >>> 10) & 31);
                var g = expand((value >>> 5) & 31);
                var b = expand(value & 31);
                var a = bits == 16 && (value & 0x8000) != 0 ? 255 : bits == 16 ? 0 : 255;
                yield a << 24 | r << 16 | g << 8 | b;
            }
            case 24 -> 0xFF000000 | (data[position + 2] & 0xFF) << 16 | (data[position + 1] & 0xFF) << 8
                    | (data[position] & 0xFF);
            default -> (data[position + 3] & 0xFF) << 24 | (data[position + 2] & 0xFF) << 16
                    | (data[position + 1] & 0xFF) << 8 | (data[position] & 0xFF);
        };
    }

    /**
     * Expands a 5-bit channel to 8 bits by bit replication.
     */
    private static int expand(int value) {
        return value << 3 | value >>> 2;
    }
}
