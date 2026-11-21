package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.HeaderVersion;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.PixelFormat;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ImageQuantization;
import com.github.auties00.smalt.image.ops.QuantizeOptions;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Arrays;
import java.util.Objects;

/**
 * Encodes pictures as Windows bitmap (BMP) files.
 *
 * <p>The picture is converted to 8-bit RGBA first ({@link ImageOps#toRgba(Picture)}). Its pixels are
 * stored in the {@link BmpEncoderOptions#pixelFormat()} layout: color table indices of 1, 4 or 8 bits
 * (optionally run-length encoded), 16-bit 5-5-5 or 5-6-5 pixels, 24-bit pixels, or 32-bit pixels with
 * an unused byte or with straight alpha. Every layout but {@link PixelFormat#ARGB_8888} ignores alpha.
 * A picture with more colors than its color table holds is reduced with
 * {@link ImageQuantization#quantize(Picture, QuantizeOptions)} at the options' method and dithering;
 * otherwise the table holds exactly the picture's colors, in order of first appearance, and the 1-,
 * 4- and 8-bit layouts are lossless. 16-bit channels are rounded to {@code round(v * (2^n - 1) / 255)}.
 * The automatic layout keeps an 8-bit picture lossless.
 *
 * <p>The file holds the 14-byte file header; the {@link BmpEncoderOptions#headerVersion()}
 * information header; for {@link PixelFormat#RGB_565} under a {@code BITMAPINFOHEADER} the three color
 * masks; the color table of a palette layout (four bytes per entry, the table holding as many entries
 * as the picture needs for an 8-bit palette, which the colors-used field states, and all 2 or 16 for a
 * 1- or 4-bit palette); the rows, each padded to a multiple of 4 bytes, bottom row first unless
 * {@link BmpEncoderOptions#isTopDown()}; and the ICC bytes of {@link BmpEncoderOptions#colorProfile()}.
 * A V4 or V5 header declares the sRGB color space, or, with a profile, the embedded profile and the
 * profile's rendering intent. Run-length encoded rows end with an end-of-line code, the last with an
 * end-of-bitmap code instead. Every field is set: the file and image sizes and the resolution.
 *
 * <p>{@link BmpDecoder} decodes every file written here back to the picture's colors exactly in the
 * lossless cases above, alpha included for {@link PixelFormat#ARGB_8888} unless every pixel is fully
 * transparent (readers take an alpha channel that is zero everywhere as unused). The output is
 * deterministic. This class is thread-safe.
 */
public final class BmpEncoder {
    /**
     * The size of the file header.
     */
    private static final int FILE_HEADER = 14;

    /**
     * The largest file a BMP header can describe, whose size field is 32 bits.
     */
    private static final long MAX_FILE = 0xFFFF_FFFFL;

    /**
     * The size of the buffer the rows are written through.
     */
    private static final int CHUNK = 1 << 16;

    /**
     * The {@code bV4CSType} of sRGB, {@code 'sRGB'}.
     */
    private static final int SRGB = 0x7352_4742;

    /**
     * The {@code bV5CSType} of an embedded profile, {@code 'MBED'}.
     */
    private static final int EMBEDDED = 0x4D42_4544;

    /**
     * Prevents instantiation.
     */
    private BmpEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link BmpEncoderOptions#defaults()}.
     *
     * @param picture the pixels to encode
     * @return the complete file
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB or the largest Java array
     * @see #encode(Picture, BmpEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, BmpEncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the pixels to encode
     * @param options the layout, compression, header, color profile and quantization settings
     * @return the complete file
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB or the largest Java array
     */
    public static byte[] encode(Picture picture, BmpEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, BmpEncoderOptions)} returns; uncompressed
     * rows are written as they are converted, without holding the whole file.
     *
     * @param picture the pixels to encode
     * @param options the layout, compression, header, color profile and quantization settings
     * @param sink the output, written from its position; not closed
     * @throws MediaException.LimitExceeded if the file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, BmpEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        var rgba = ImageOps.toRgba(picture);
        var requested = options.headerVersion().orElse(null);
        var format = options.pixelFormat().orElseGet(() -> choose(rgba, options.isRunLength(),
                requested != HeaderVersion.INFO));
        var version = requested != null ? requested : format == PixelFormat.ARGB_8888
                || options.colorProfile().isPresent() ? HeaderVersion.V5 : HeaderVersion.INFO;
        new Writer(rgba, format, version, options).write(sink);
    }

    /**
     * Chooses the automatic pixel format.
     *
     * @param picture the 8-bit picture to encode
     * @param runLength whether the indices are run-length encoded
     * @param alpha whether the header can hold an alpha mask
     * @return the smallest lossless layout
     */
    private static PixelFormat choose(RgbaPicture picture, boolean runLength, boolean alpha) {
        if (!runLength && alpha && hasTranslucency(picture)) {
            return PixelFormat.ARGB_8888;
        }
        var colors = countColors(picture, 257);
        if (runLength) {
            return colors <= 16 ? PixelFormat.PALETTE_4 : PixelFormat.PALETTE_8;
        }
        if (colors <= 2) {
            return PixelFormat.PALETTE_1;
        }
        if (colors <= 16) {
            return PixelFormat.PALETTE_4;
        }
        return colors <= 256 ? PixelFormat.PALETTE_8 : PixelFormat.RGB_888;
    }

    /**
     * {@return whether a picture has alpha and some pixel is not opaque}
     *
     * @param picture the 8-bit picture to inspect
     */
    private static boolean hasTranslucency(RgbaPicture picture) {
        if (!picture.hasAlpha()) {
            return false;
        }
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            var at = y * picture.stride() + 3;
            for (var x = 0; x < picture.width(); x++, at += 4) {
                if (pixels[at] != (byte) 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts the distinct colors of a picture, ignoring alpha, up to a bound.
     *
     * @param picture the 8-bit picture to inspect
     * @param bound the count at which counting stops
     * @return the number of colors, at most {@code bound}
     */
    private static int countColors(RgbaPicture picture, int bound) {
        var table = new int[1024];
        Arrays.fill(table, -1);
        var count = 0;
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            var at = y * picture.stride();
            for (var x = 0; x < picture.width(); x++, at += 4) {
                var color = (pixels[at] & 0xFF) << 16 | (pixels[at + 1] & 0xFF) << 8 | (pixels[at + 2] & 0xFF);
                var slot = (color * 0x9E37_79B1) >>> 22;
                while (table[slot] != -1 && table[slot] != color) {
                    slot = (slot + 1) & 1023;
                }
                if (table[slot] == -1) {
                    table[slot] = color;
                    if (++count >= bound) {
                        return count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Writes one file.
     */
    private static final class Writer {
        private final RgbaPicture picture;

        private final PixelFormat format;

        private final HeaderVersion version;

        private final BmpEncoderOptions options;

        private final int bitCount;

        private final int headerSize;

        /**
         * The color table as {@code 0xAARRGGBB} entries, or {@code null}.
         */
        private int[] palette;

        /**
         * The color table index of every pixel, row by row, or {@code null}.
         */
        private byte[] indices;

        /**
         * Constructs the writer.
         *
         * @param picture the pixels
         * @param format the layout
         * @param version the information header
         * @param options the remaining settings
         */
        Writer(RgbaPicture picture, PixelFormat format, HeaderVersion version, BmpEncoderOptions options) {
            this.picture = picture;
            this.format = format;
            this.version = version;
            this.options = options;
            this.bitCount = switch (format) {
                case PALETTE_1 -> 1;
                case PALETTE_4 -> 4;
                case PALETTE_8 -> 8;
                case RGB_555, RGB_565 -> 16;
                case RGB_888 -> 24;
                case XRGB_8888, ARGB_8888 -> 32;
            };
            this.headerSize = switch (version) {
                case INFO -> 40;
                case V4 -> 108;
                case V5 -> 124;
            };
        }

        /**
         * Writes the file.
         *
         * @param sink the output
         */
        void write(MediaSink sink) {
            var width = picture.width();
            var height = picture.height();
            if (bitCount <= 8) {
                quantize();
            }
            var stride = ((long) width * bitCount + 31) / 32 * 4;
            var compressed = options.isRunLength() ? runLength() : null;
            var dataSize = compressed != null ? compressed.length : stride * height;
            var masks = format == PixelFormat.RGB_565 && version == HeaderVersion.INFO ? 12 : 0;
            var entries = palette == null ? 0 : bitCount == 8 ? palette.length : 1 << bitCount;
            var dataOffset = FILE_HEADER + headerSize + masks + entries * 4L;
            var profile = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
            var fileSize = dataOffset + dataSize + (profile == null ? 0 : profile.length);
            if (fileSize > MAX_FILE || stride > Integer.MAX_VALUE - 8) {
                throw new MediaException.LimitExceeded("a " + width + "x" + height + " BMP file would exceed 4 GiB");
            }
            var header = new byte[(int) dataOffset];
            header[0] = 'B';
            header[1] = 'M';
            ByteArrays.put32le(header, 2, fileSize);
            ByteArrays.put32le(header, 10, dataOffset);
            var info = FILE_HEADER;
            ByteArrays.put32le(header, info, headerSize);
            ByteArrays.put32le(header, info + 4, width);
            ByteArrays.put32le(header, info + 8, options.isTopDown() ? -(long) height : height);
            ByteArrays.put16le(header, info + 12, 1);
            ByteArrays.put16le(header, info + 14, bitCount);
            ByteArrays.put32le(header, info + 16, compression());
            ByteArrays.put32le(header, info + 20, dataSize);
            var pixelsPerMeter = options.pixelsPerMeter();
            ByteArrays.put32le(header, info + 24, pixelsPerMeter[0]);
            ByteArrays.put32le(header, info + 28, pixelsPerMeter[1]);
            ByteArrays.put32le(header, info + 32, palette == null ? 0 : palette.length);
            var bitFields = bitFields();
            if (bitFields != null) {
                // the masks follow a BITMAPINFOHEADER and fill the same bytes of a V4 or V5 header
                for (var i = 0; i < (version == HeaderVersion.INFO ? 3 : 4); i++) {
                    ByteArrays.put32le(header, info + 40 + i * 4, bitFields[i]);
                }
            }
            if (version != HeaderVersion.INFO) {
                ByteArrays.put32le(header, info + 56, profile != null ? EMBEDDED : SRGB);
            }
            if (version == HeaderVersion.V5) {
                ByteArrays.put32le(header, info + 108, intent(options.colorProfile().orElse(null)));
                if (profile != null) {
                    ByteArrays.put32le(header, info + 112, dataOffset + dataSize - FILE_HEADER);
                    ByteArrays.put32le(header, info + 116, profile.length);
                }
            }
            if (palette != null) {
                var at = FILE_HEADER + headerSize + masks;
                for (var i = 0; i < palette.length; i++) {
                    var color = palette[i];
                    header[at + i * 4] = (byte) color;
                    header[at + i * 4 + 1] = (byte) (color >>> 8);
                    header[at + i * 4 + 2] = (byte) (color >>> 16);
                }
            }
            sink.write(header);
            if (compressed != null) {
                sink.write(compressed);
            } else {
                writeRows(sink, (int) stride);
            }
            if (profile != null) {
                sink.write(profile);
            }
        }

        /**
         * {@return the stored compression value}
         */
        private int compression() {
            if (options.isRunLength()) {
                return bitCount == 8 ? 1 : 2;
            }
            return format == PixelFormat.RGB_565 || format == PixelFormat.ARGB_8888 ? 3 : 0;
        }

        /**
         * {@return the red, green, blue and alpha masks of a {@code BI_BITFIELDS} layout, or {@code null}}
         */
        private int[] bitFields() {
            return switch (format) {
                case RGB_565 -> new int[]{0xF800, 0x07E0, 0x001F, 0};
                case ARGB_8888 -> new int[]{0x00FF_0000, 0x0000_FF00, 0x0000_00FF, 0xFF00_0000};
                default -> null;
            };
        }

        /**
         * Maps the rendering intent of a profile to its {@code bV5Intent} value.
         *
         * @param profile the profile, or {@code null}
         * @return {@code LCS_GM_IMAGES} for perceptual and without a profile, {@code LCS_GM_GRAPHICS} for
         * relative colorimetric, {@code LCS_GM_BUSINESS} for saturation and
         * {@code LCS_GM_ABS_COLORIMETRIC} for absolute colorimetric
         */
        private static int intent(ColorProfile profile) {
            if (profile == null) {
                return 4;
            }
            return switch (profile.renderingIntent()) {
                case PERCEPTUAL -> 4;
                case RELATIVE_COLORIMETRIC -> 2;
                case SATURATION -> 1;
                case ABSOLUTE_COLORIMETRIC -> 8;
            };
        }

        /**
         * Builds the color table and the index of every pixel.
         */
        private void quantize() {
            var opaque = RgbaPicture.of(picture.width(), picture.height(), picture.stride(), picture.pixels(), false);
            var quantized = ImageQuantization.quantize(opaque, QuantizeOptions.builder().colors(1 << bitCount)
                    .method(options.method()).dithering(options.dithering()).build());
            palette = quantized.palette();
            indices = quantized.indices();
        }

        /**
         * Converts and writes the uncompressed rows, in stored order.
         *
         * @param sink the output
         * @param stride the stored row size in bytes
         */
        private void writeRows(MediaSink sink, int stride) {
            var height = picture.height();
            var rowsPerChunk = Math.max(1, CHUNK / stride);
            var chunk = new byte[stride * Math.min(rowsPerChunk, height)];
            var filled = 0;
            for (var stored = 0; stored < height; stored++) {
                var y = options.isTopDown() ? stored : height - 1 - stored;
                Arrays.fill(chunk, filled, filled + stride, (byte) 0);
                convert(y, chunk, filled);
                filled += stride;
                if (filled == chunk.length) {
                    sink.write(chunk, 0, filled);
                    filled = 0;
                }
            }
            if (filled > 0) {
                sink.write(chunk, 0, filled);
            }
        }

        /**
         * Converts one row to its stored form.
         *
         * @param y the row of the picture
         * @param out the destination, zero over the row
         * @param at the index of the row's first byte
         */
        private void convert(int y, byte[] out, int at) {
            var width = picture.width();
            var pixels = picture.pixels();
            var source = y * picture.stride();
            switch (format) {
                case PALETTE_1, PALETTE_4, PALETTE_8 -> {
                    var base = y * width;
                    if (bitCount == 8) {
                        System.arraycopy(indices, base, out, at, width);
                    } else {
                        var perByte = 8 / bitCount;
                        for (var x = 0; x < width; x++) {
                            var shift = 8 - bitCount - (x % perByte) * bitCount;
                            out[at + x / perByte] |= (byte) ((indices[base + x] & 0xFF) << shift);
                        }
                    }
                }
                case RGB_555, RGB_565 -> {
                    var six = format == PixelFormat.RGB_565;
                    for (var x = 0; x < width; x++, source += 4) {
                        var r = ((pixels[source] & 0xFF) * 31 + 127) / 255;
                        var g = six ? ((pixels[source + 1] & 0xFF) * 63 + 127) / 255
                                : ((pixels[source + 1] & 0xFF) * 31 + 127) / 255;
                        var b = ((pixels[source + 2] & 0xFF) * 31 + 127) / 255;
                        var value = six ? r << 11 | g << 5 | b : r << 10 | g << 5 | b;
                        out[at + x * 2] = (byte) value;
                        out[at + x * 2 + 1] = (byte) (value >>> 8);
                    }
                }
                case RGB_888 -> {
                    for (var x = 0; x < width; x++, source += 4) {
                        var t = at + x * 3;
                        out[t] = pixels[source + 2];
                        out[t + 1] = pixels[source + 1];
                        out[t + 2] = pixels[source];
                    }
                }
                case XRGB_8888, ARGB_8888 -> {
                    var alpha = format == PixelFormat.ARGB_8888 && picture.hasAlpha();
                    var fill = format == PixelFormat.ARGB_8888 ? (byte) 0xFF : 0;
                    for (var x = 0; x < width; x++, source += 4) {
                        var t = at + x * 4;
                        out[t] = pixels[source + 2];
                        out[t + 1] = pixels[source + 1];
                        out[t + 2] = pixels[source];
                        out[t + 3] = alpha ? pixels[source + 3] : fill;
                    }
                }
            }
        }

        /**
         * Run-length encodes the indices, bottom row first.
         *
         * @return the encoded rows
         */
        private byte[] runLength() {
            var width = picture.width();
            var height = picture.height();
            var out = new Output(width * height / 2 + height * 4 + 16);
            for (var stored = 0; stored < height; stored++) {
                var base = (height - 1 - stored) * width;
                if (bitCount == 8) {
                    encodeRle8(out, base, width);
                } else {
                    encodeRle4(out, base, width);
                }
                out.add(0);
                out.add(stored == height - 1 ? 1 : 0);
            }
            return out.toByteArray();
        }

        /**
         * Encodes one row of 8-bit indices.
         *
         * @param out the output
         * @param base the index of the row's first pixel in {@link #indices}
         * @param width the number of pixels
         */
        private void encodeRle8(Output out, int base, int width) {
            var x = 0;
            while (x < width) {
                var value = indices[base + x];
                var run = 1;
                while (x + run < width && run < 255 && indices[base + x + run] == value) {
                    run++;
                }
                if (run >= 2) {
                    out.add(run);
                    out.add(value);
                    x += run;
                    continue;
                }
                var start = x;
                while (x < width && x - start < 255 && !(x + 2 < width && indices[base + x] == indices[base + x + 1]
                        && indices[base + x] == indices[base + x + 2])) {
                    x++;
                }
                var count = x - start;
                if (count >= 3) {
                    out.add(0);
                    out.add(count);
                    for (var i = 0; i < count; i++) {
                        out.add(indices[base + start + i]);
                    }
                    if ((count & 1) != 0) {
                        out.add(0);
                    }
                } else {
                    for (var i = 0; i < count; i++) {
                        out.add(1);
                        out.add(indices[base + start + i]);
                    }
                }
            }
        }

        /**
         * Encodes one row of 4-bit indices.
         *
         * @param out the output
         * @param base the index of the row's first pixel in {@link #indices}
         * @param width the number of pixels
         */
        private void encodeRle4(Output out, int base, int width) {
            var x = 0;
            while (x < width) {
                var run = alternating(base, x, width);
                if (run >= 4) {
                    var first = indices[base + x] & 0xF;
                    var second = run > 1 ? indices[base + x + 1] & 0xF : first;
                    out.add(run);
                    out.add(first << 4 | second);
                    x += run;
                    continue;
                }
                var start = x;
                while (x < width && x - start < 255 && alternating(base, x, width) < 4) {
                    x++;
                }
                var count = x - start;
                if (count >= 3) {
                    out.add(0);
                    out.add(count);
                    for (var i = 0; i < count; i += 2) {
                        var high = indices[base + start + i] & 0xF;
                        var low = i + 1 < count ? indices[base + start + i + 1] & 0xF : 0;
                        out.add(high << 4 | low);
                    }
                    if (((count + 1) / 2 & 1) != 0) {
                        out.add(0);
                    }
                } else {
                    var first = indices[base + start] & 0xF;
                    var second = count > 1 ? indices[base + start + 1] & 0xF : first;
                    out.add(count);
                    out.add(first << 4 | second);
                }
            }
        }

        /**
         * Measures the run of pixels alternating between two values that starts at a pixel.
         *
         * @param base the index of the row's first pixel in {@link #indices}
         * @param x the first pixel of the run
         * @param width the number of pixels of the row
         * @return the run length, from 1 to 255
         */
        private int alternating(int base, int x, int width) {
            var run = 1;
            while (x + run < width && run < 255 && (run < 2 || indices[base + x + run] == indices[base + x + run - 2])) {
                run++;
            }
            return run;
        }
    }

    /**
     * A growable byte buffer.
     */
    private static final class Output {
        private byte[] data;

        private int length;

        /**
         * Constructs the buffer.
         *
         * @param capacity the initial capacity
         */
        Output(int capacity) {
            this.data = new byte[Math.max(16, capacity)];
        }

        /**
         * Appends one byte.
         *
         * @param value the byte, in its low 8 bits
         */
        void add(int value) {
            if (length == data.length) {
                if (data.length >= Integer.MAX_VALUE - 16) {
                    throw new MediaException.LimitExceeded("the run-length encoded pixels exceed the largest Java array");
                }
                data = Arrays.copyOf(data, (int) Math.min(Integer.MAX_VALUE - 16, data.length * 2L));
            }
            data[length++] = (byte) value;
        }

        /**
         * {@return a copy of the bytes appended}
         */
        byte[] toByteArray() {
            return Arrays.copyOf(data, length);
        }
    }
}
